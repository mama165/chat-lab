// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/observability"
	pb "chat-lab/proto/analyzer"
	"chat-lab/runtime/workers"
	"chat-lab/sink"
	"context"
	"log/slog"
	"sync"
	"time"
)

type Orchestrator struct {
	mu                     sync.Mutex
	log                    *slog.Logger
	globalMonitor          *domain.GlobalMonitoring
	counter                *event.Counter
	numWorkers             int
	supervisor             contract.ISupervisor
	eventChan              chan event.Event
	telemetryChan          chan event.Event
	processTrackerChan     chan domain.Process
	fileRequestChan        chan domain.FileDownloaderRequest
	tmpFileLocationChan    chan domain.TmpFileLocation
	specialistResponseChan chan domain.SpecialistResponse
	analysisRepository     storage.IAnalysisRepository
	fileTaskRepository     storage.IFileTaskRepository
	coordinator            contract.SpecialistCoordinator
	grpcClient             pb.FileDownloaderServiceClient
	fileAccumulator        contract.IFileAccumulator
	monitoring             *observability.MonitoringManager
	bufferTimeout          time.Duration
	specialistTimeout      time.Duration
	metricInterval         time.Duration
	lowCapacityThreshold   int
	maxAnalyzedEvent       int
	fileTransferInterval   time.Duration
	pendingFileBatchSize   int
	maxFileToProcess       uint32
	fileTmpJobInterval     time.Duration
}

func NewOrchestrator(
	log *slog.Logger,
	globalMonitor *domain.GlobalMonitoring,
	supervisor *workers.Supervisor,
	telemetryChan, eventChan chan event.Event,
	processTrackerChan chan domain.Process,
	fileRequestChan chan domain.FileDownloaderRequest,
	tmpFileLocationChan chan domain.TmpFileLocation,
	specialistResponseChan chan domain.SpecialistResponse,
	analysisRepository storage.IAnalysisRepository,
	fileTaskRepository storage.IFileTaskRepository,
	specialistCoordinator contract.SpecialistCoordinator,
	grpcClient pb.FileDownloaderServiceClient,
	fileAccumulator contract.IFileAccumulator,
	monitoring *observability.MonitoringManager,
	numWorkers int,
	bufferTimeout, specialistTimeout, metricInterval time.Duration,
	lowCapacityThreshold, maxAnalyzedEvent int,
	fileTransferInterval time.Duration, pendingFileBatchSize int,
	maxFileToProcess uint32,
	fileTmpJobInterval time.Duration,
) *Orchestrator {
	return &Orchestrator{
		log:                    log,
		globalMonitor:          globalMonitor,
		counter:                event.NewCounter(),
		numWorkers:             numWorkers,
		supervisor:             supervisor,
		telemetryChan:          telemetryChan,
		processTrackerChan:     processTrackerChan,
		fileRequestChan:        fileRequestChan,
		tmpFileLocationChan:    tmpFileLocationChan,
		specialistResponseChan: specialistResponseChan,
		eventChan:              eventChan,
		analysisRepository:     analysisRepository,
		fileTaskRepository:     fileTaskRepository,
		coordinator:            specialistCoordinator,
		grpcClient:             grpcClient,
		fileAccumulator:        fileAccumulator,
		monitoring:             monitoring,
		bufferTimeout:          bufferTimeout,
		specialistTimeout:      specialistTimeout,
		metricInterval:         metricInterval,
		lowCapacityThreshold:   lowCapacityThreshold,
		maxAnalyzedEvent:       maxAnalyzedEvent,
		fileTransferInterval:   fileTransferInterval,
		pendingFileBatchSize:   pendingFileBatchSize,
		maxFileToProcess:       maxFileToProcess,
		fileTmpJobInterval:     fileTmpJobInterval,
	}
}

// Start initiates the orchestrator by preparing all components (workers, moderation, pipeline)
// and then starting the supervisor. It uses a preparation pattern to minimize mutex locking time.
func (o *Orchestrator) Start(ctx context.Context) error {
	// 1. Preparation phase (No Lock)
	fileTransferWorker, fileDownloaderWorker, fileTmpJobWorker := o.prepareFileTransferWorker()

	fanoutWorkers := o.PrepareFanouts()
	channelCapWorker, telemetryWorker := o.prepareTelemetry()
	heathMonitoringWorker := o.prepareHeathMonitoringWorker()

	// 2. Critical Section (Short Lock)
	// We only lock to update the internal state and the supervisor.
	o.mu.Lock()

	// Registering all workers to the supervisor
	o.supervisor.Add(
		channelCapWorker,
		telemetryWorker,
		heathMonitoringWorker,
	)
	o.supervisor.Add(fanoutWorkers...)
	o.supervisor.Add(fileTransferWorker)
	o.supervisor.Add(fileDownloaderWorker)
	o.supervisor.Add(fileTmpJobWorker)

	o.mu.Unlock()

	// 3. Execution phase (No Lock)
	o.log.Info("Starting orchestrator and all supervised workers")
	o.supervisor.Run(ctx)
	return nil
}

func (o *Orchestrator) prepareFileTransferWorker() (contract.Worker, contract.Worker, contract.Worker) {
	fileTransferWorker := workers.NewFileTransferWorker(
		o.fileRequestChan,
		o.fileTaskRepository,
		o.log,
		o.fileTransferInterval,
		o.pendingFileBatchSize,
	)

	fileDownloaderWorker := workers.NewFileDownloaderMasterWorker(
		o.log,
		o.fileRequestChan,
		o.grpcClient,
		o.fileAccumulator,
	)

	fileTmpJobWorker := workers.NewFileTmpJobWorker(
		o.coordinator,
		o.log,
		o.maxFileToProcess,
		o.fileTmpJobInterval,
		o.eventChan,
		o.tmpFileLocationChan,
		o.specialistResponseChan,
	)
	return fileTransferWorker, fileDownloaderWorker, fileTmpJobWorker
}

// PrepareFanouts initializes the sinks and the fanout workers.
// Fanout worker handles a lot of events
func (o *Orchestrator) PrepareFanouts() []contract.Worker {
	analysisSink := sink.NewAnalysisSink(
		o.analysisRepository, o.fileTaskRepository, o.log,
		o.maxAnalyzedEvent, o.bufferTimeout, o.specialistTimeout)

	var res []contract.Worker
	for i := 0; i < o.numWorkers; i++ {
		res = append(res, workers.NewEventFanoutWorker(
			o.log,
			analysisSink,
			o.eventChan,
			o.telemetryChan,
		))
	}
	return res
}

func (o *Orchestrator) prepareTelemetry() (contract.Worker, contract.Worker) {
	handlers := []event.Handler{
		event.NewChannelCapacityHandler(o.log, o.lowCapacityThreshold, o.monitoring),
		event.NewProcessTrackerHandler(o.log),
		event.NewWorkerRestartedAfterPanicHandler(o.log, o.counter),
	}
	channels := []workers.NamedChannel{
		{Name: "EventChan", Channel: o.eventChan},
		{Name: "TelemetryChan", Channel: o.telemetryChan},
		{Name: "ProcessTrackerChan", Channel: o.processTrackerChan},
		{Name: "FileRequestChan", Channel: o.fileRequestChan},
		{Name: "TmpFileLocation", Channel: o.tmpFileLocationChan},
		{Name: "SpecialistResponse", Channel: o.specialistResponseChan},
	}
	channelCapWorker := workers.NewChannelCapacityWorker(
		o.log, channels, o.telemetryChan,
		o.metricInterval,
	)
	telemetryWorker := workers.NewTelemetryWorker(o.log, o.metricInterval, o.telemetryChan, handlers)

	return channelCapWorker, telemetryWorker
}

func (o *Orchestrator) prepareHeathMonitoringWorker() contract.Worker {
	return workers.NewHealthMonitoringWorker(
		o.log,
		o.globalMonitor,
		o.telemetryChan,
		o.processTrackerChan,
		o.metricInterval,
	)
}

// Stop initiates a graceful shutdown of the orchestrator.
// It cancels the supervision context to signal workers to stop,
// and then closes internal channels to ensure all remaining events are drained.
func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")

	// 1. Cancel the supervised context.
	// This immediately signals all workers to stop blocking on operations.
	o.supervisor.Stop()

	close(o.eventChan)

	o.log.Debug("Orchestrator internal channels closed")
}
