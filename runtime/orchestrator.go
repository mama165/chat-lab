// Package runtime wires the pipeline together: it scans a folder, stores what it
// finds, has the specialists analyze each file, and observes the whole thing.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/internal"
	"chat-lab/observability"
	"chat-lab/runtime/workers"
	"chat-lab/sink"
	"context"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"sync/atomic"
	"time"
)

// Orchestrator owns the file pipeline. Every stage is a pool of supervised
// workers, and the stages are linked by bounded channels:
//
//	scanner -> fileChan -> ingest -> eventChan -> fan-out -> analysis sink (storage)
//	                                                              |
//	                                                           jobChan
//	                                                              v
//	                                              analysis workers -> specialists -> storage
//
// Because every channel is bounded and the flow never loops back, a slow stage
// naturally slows down the stages before it, all the way up to the scanner.
type Orchestrator struct {
	mu                 sync.Mutex
	log                *slog.Logger
	config             internal.Config
	globalMonitor      *domain.GlobalMonitoring
	counter            *event.Counter
	supervisor         contract.ISupervisor
	eventChan          chan event.Event
	telemetryChan      chan event.Event
	processTrackerChan chan domain.Process
	// dirChan is the queue of directories still to explore.
	dirChan chan string
	// fileChan carries the files discovered by the scanner.
	fileChan chan *analyzer.FileAnalyzerRequest
	// jobChan carries the stored files waiting for the specialists.
	jobChan chan domain.AnalysisJob
	// scanWG counts the directories still to explore; scanDone is closed when it reaches zero.
	scanWG       sync.WaitGroup
	scanDone     chan struct{}
	analysisSink *sink.AnalysisSink
	coordinator  contract.SpecialistCoordinator
	ingester     workers.FileIngester
	monitoring   *observability.MonitoringManager
}

func NewOrchestrator(
	log *slog.Logger,
	config internal.Config,
	globalMonitor *domain.GlobalMonitoring,
	supervisor *workers.Supervisor,
	telemetryChan, eventChan chan event.Event,
	processTrackerChan chan domain.Process,
	analysisRepository storage.IAnalysisRepository,
	specialistCoordinator contract.SpecialistCoordinator,
	ingester workers.FileIngester,
	monitoring *observability.MonitoringManager,
) *Orchestrator {
	// The scan is seeded by writing the root folder to dirChan before any worker
	// runs, so this queue needs room for at least one entry.
	queueSize := max(config.BufferSize, 1)
	jobChan := make(chan domain.AnalysisJob, queueSize)

	return &Orchestrator{
		log:                log,
		config:             config,
		globalMonitor:      globalMonitor,
		counter:            event.NewCounter(),
		supervisor:         supervisor,
		telemetryChan:      telemetryChan,
		eventChan:          eventChan,
		processTrackerChan: processTrackerChan,
		dirChan:            make(chan string, queueSize),
		fileChan:           make(chan *analyzer.FileAnalyzerRequest, queueSize),
		jobChan:            jobChan,
		scanDone:           make(chan struct{}),
		analysisSink: sink.NewAnalysisSink(
			analysisRepository, jobChan, log,
			config.MaxAnalyzedEvent, config.BufferTimeout, config.SpecialistTimeout),
		coordinator: specialistCoordinator,
		ingester:    ingester,
		monitoring:  monitoring,
	}
}

// Start prepares all the workers, launches the scan of the configured folder and
// blocks until the supervisor stops (context canceled or Stop called).
func (o *Orchestrator) Start(ctx context.Context) error {
	root := o.config.ScanRootDir
	info, err := os.Stat(root)
	if err != nil {
		return fmt.Errorf("unable to scan %q: %w", root, err)
	}
	if !info.IsDir() {
		return fmt.Errorf("unable to scan %q: not a directory", root)
	}

	// 1. Preparation phase (No Lock)
	scanWorkers := o.prepareScanWorkers()
	analysisWorkers := o.prepareAnalysisWorkers()
	fanoutWorkers := o.PrepareFanouts()
	channelCapWorker, telemetryWorker := o.prepareTelemetry()
	healthMonitoringWorker := o.prepareHealthMonitoringWorker()
	heartbeatWorker := workers.NewHeartbeatWorker(
		o.log, o.globalMonitor, o.monitoring, o.config.MetricInterval)

	// 2. Critical Section (Short Lock)
	// We only lock to update the supervisor.
	o.mu.Lock()
	o.supervisor.Add(
		channelCapWorker,
		telemetryWorker,
		healthMonitoringWorker,
		heartbeatWorker,
	)
	o.supervisor.Add(fanoutWorkers...)
	o.supervisor.Add(analysisWorkers...)
	o.supervisor.Add(scanWorkers...)
	o.mu.Unlock()

	// 3. Seed the scan: the root folder is the first directory to explore.
	startedAt := time.Now()
	o.scanWG.Add(1)
	o.dirChan <- root
	go o.waitForScanCompletion(root, startedAt)
	go o.monitoring.Listen(ctx, nil)

	// 4. Execution phase (No Lock)
	o.log.Info("Starting orchestrator and all supervised workers", "scan_root", root)
	o.supervisor.Run(ctx)
	return nil
}

// ScanDone is closed once every directory under the root folder has been explored.
// The files found may still be waiting for the specialists at that point.
func (o *Orchestrator) ScanDone() <-chan struct{} {
	return o.scanDone
}

func (o *Orchestrator) waitForScanCompletion(root string, startedAt time.Time) {
	o.scanWG.Wait()
	close(o.scanDone)
	o.log.Info("Scan completed",
		"root", root,
		"duration", time.Since(startedAt).Round(time.Millisecond),
		"files", atomic.LoadUint64(&o.monitoring.FilesFound),
		"dirs", atomic.LoadUint64(&o.monitoring.DirsScanned),
		"errors", atomic.LoadUint64(&o.monitoring.ErrorCount),
		"skipped", atomic.LoadUint64(&o.monitoring.SkippedItem),
	)
}

// prepareScanWorkers creates the workers that walk the folder tree, plus the one
// that feeds what they find into the pipeline.
func (o *Orchestrator) prepareScanWorkers() []contract.Worker {
	res := make([]contract.Worker, 0, o.config.ScannerWorkerNb+1)
	for i := 0; i < o.config.ScannerWorkerNb; i++ {
		res = append(res, workers.NewFileScannerWorker(
			o.log, o.config.DriveID, o.monitoring,
			o.dirChan, o.fileChan,
			&o.scanWG,
			o.config.ScannerBackpressureLowThreshold,
			o.config.ScannerBackpressureHardThreshold,
		))
	}
	res = append(res, workers.NewFileIngestWorker(o.log, o.fileChan, o.ingester))
	return res
}

// prepareAnalysisWorkers creates the workers that send files to the specialists.
func (o *Orchestrator) prepareAnalysisWorkers() []contract.Worker {
	res := make([]contract.Worker, 0, o.config.AnalysisWorkerNb)
	for i := 0; i < o.config.AnalysisWorkerNb; i++ {
		res = append(res, workers.NewFileAnalysisWorker(
			o.log, o.coordinator, o.analysisSink, o.monitoring,
			o.jobChan, o.telemetryChan,
		))
	}
	return res
}

// PrepareFanouts initializes the fan-out workers, which route events to the analysis sink.
func (o *Orchestrator) PrepareFanouts() []contract.Worker {
	var res []contract.Worker
	for i := 0; i < o.config.NumberOfWorkers; i++ {
		res = append(res, workers.NewEventFanoutWorker(
			o.log,
			o.analysisSink,
			o.eventChan,
			o.telemetryChan,
		))
	}
	return res
}

func (o *Orchestrator) prepareTelemetry() (contract.Worker, contract.Worker) {
	handlers := []event.Handler{
		event.NewChannelCapacityHandler(o.log, o.config.LowCapacityThreshold, o.monitoring),
		event.NewProcessTrackerHandler(o.log),
		event.NewWorkerRestartedAfterPanicHandler(o.log, o.counter),
	}
	channels := []workers.NamedChannel{
		{Name: "DirChan", Channel: o.dirChan},
		{Name: "FileChan", Channel: o.fileChan},
		{Name: "EventChan", Channel: o.eventChan},
		{Name: "AnalysisJobChan", Channel: o.jobChan},
		{Name: "TelemetryChan", Channel: o.telemetryChan},
		{Name: "ProcessTrackerChan", Channel: o.processTrackerChan},
	}
	channelCapWorker := workers.NewChannelCapacityWorker(
		o.log, channels, o.telemetryChan,
		o.config.MetricInterval,
	)
	telemetryWorker := workers.NewTelemetryWorker(o.log, o.config.MetricInterval, o.telemetryChan, handlers)

	return channelCapWorker, telemetryWorker
}

func (o *Orchestrator) prepareHealthMonitoringWorker() contract.Worker {
	return workers.NewHealthMonitoringWorker(
		o.log,
		o.globalMonitor,
		o.telemetryChan,
		o.processTrackerChan,
		o.config.MetricInterval,
	)
}

// Stop initiates the shutdown of the orchestrator: it cancels the supervised
// context, which every worker listens to, and releases anything still waiting
// to hand a file over for analysis. Start returns once all workers have exited.
func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")
	o.supervisor.Stop()
	o.analysisSink.Close()
}
