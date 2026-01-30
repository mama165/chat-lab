package main

import (
	"chat-lab/contract"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/grpc/client"
	"chat-lab/internal"
	"chat-lab/runtime/workers"
	"context"
	"fmt"
	"github.com/Netflix/go-env"
	"github.com/mama165/sdk-go/logs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"log"
	"log/slog"
	"os"
	"os/signal"
	"runtime"
	"sync"
	"syscall"
)

func main() {
	// 1. Parsing des options (root, server addr, parallel)
	/*	rootDir := flag.String("root", ".", "target directory")
		address := flag.String("address", "localhost:8080", "gRPC listen address")
		goroutineNbr := flag.Int("parallel", runtime.NumCPU(), "number of concurrent goroutines")
		driveID := flag.String("driveID", "os-main", "disk identifier for Badger")
		flag.Parse()*/

	var config internal.Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		log.Fatalf("config error: %v", err)
	}

	if config.ScannerWorkerNb < runtime.NumCPU() {
		runtime.GOMAXPROCS(config.ScannerWorkerNb)
	} else {
		runtime.GOMAXPROCS(runtime.NumCPU() / 2)
	}

	logger := logs.GetLoggerFromString(config.LogLevel)

	address := fmt.Sprintf("%s:%d", config.Host, config.Port)

	conn, err := grpc.NewClient(address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Fail to connect with Master: %v", err)
	}
	defer conn.Close()

	grpcClient := client.NewFileAnalyzerClient(conn)

	ctx := context.Background()
	// 2. Setup context to handle termination signals (Ctrl+C).
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	telemetryChan := make(chan event.Event, 1000)
	supervisor := workers.NewSupervisor(logger, telemetryChan, config.RestartInterval)
	counter := workers.NewCounterFileScanner()
	dirChan := make(chan string, config.BufferSize)
	fileChan := make(chan *analyzer.FileAnalyzerRequest, config.BufferSize)

	var scanWG sync.WaitGroup
	var workersWG sync.WaitGroup

	telemetryWorkers, channelCapWorker :=
		buildTelemetryWorkers(config, logger, fileChan, dirChan, telemetryChan)

	fileScannerWorkers, fileSenderWorker :=
		buildFileWorkers(
			config,
			&workersWG, &scanWG, logger, counter, dirChan,
			fileChan, grpcClient)
	supervisor.
		Add(telemetryWorkers, channelCapWorker).
		Add(fileScannerWorkers...).
		Add(fileSenderWorker)

	go func() {
		logger.Info("Starting supervisor and all workers")
		supervisor.Run(ctx)
	}()

	scanWG.Add(1)
	dirChan <- config.RootDir

	scanDone := make(chan struct{})
	go func() {
		logger.Info("Waiting for scan to complete...")
		scanWG.Wait()
		close(scanDone)
		logger.Info("Logic: No more directories to scan. Closing dirChan.")
	}()

	logger.Info("Scan still alive... (Ctrl+C to stop)")

	select {
	case <-ctx.Done():
		logger.Warn("Scan interrupted...")
	case <-scanDone:
		logger.Info("Scan terminated gracefully")
		close(dirChan)
	}

	workersWG.Wait()
	close(fileChan)

	logger.Info("Scan Summary",
		"Files", counter.FilesScanned,
		"Dirs", counter.DirsScanned,
		"Bytes", counter.BytesProcessed,
		"Errors", counter.ErrorCount,
		"Skipped", counter.SkippedItems,
	)
}

func buildTelemetryWorkers(
	config internal.Config,
	logger *slog.Logger,
	fileChan chan *analyzer.FileAnalyzerRequest,
	dirChan chan string,
	telemetryChan chan event.Event) (contract.Worker, contract.Worker) {
	channelCapacityHandler := event.NewChannelCapacityHandler(logger, config.LowCapacityThreshold)
	telemetryWorker := workers.NewTelemetryWorker(
		logger, config.MetricInterval, telemetryChan,
		[]event.Handler{channelCapacityHandler},
	)
	channelsToMonitor := []workers.NamedChannel{
		{Name: "FileChan", Channel: fileChan},
		{Name: "DirChan", Channel: dirChan},
	}

	channelCapacityWorker := workers.NewChannelCapacityWorker(
		logger, channelsToMonitor, telemetryChan, config.MetricInterval)

	return telemetryWorker, channelCapacityWorker
}

func buildFileWorkers(config internal.Config,
	workersWG, scanWG *sync.WaitGroup,
	logger *slog.Logger,
	counter *workers.CounterFileScanner,
	dirChan chan string, fileChan chan *analyzer.FileAnalyzerRequest,
	client client.FileAnalyzerClient) ([]contract.Worker, contract.Worker) {

	var allWorkers = make([]contract.Worker, 0, config.ScannerWorkerNb)
	for i := 0; i < config.ScannerWorkerNb; i++ {
		workersWG.Add(1)
		allWorkers = append(allWorkers,
			workers.NewFileScannerWorker(
				logger,
				config.RootDir, config.DriveID, counter,
				dirChan, fileChan,
				scanWG,
				workersWG,
				config.ScannerBackpressureLowThreshold,
				config.ScannerBackpressureHardThreshold,
			),
		)
	}
	fileSenderWorker := workers.NewFileSenderWorker(client, logger, fileChan)
	return allWorkers, fileSenderWorker
}
