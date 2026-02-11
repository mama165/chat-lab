package main

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/grpc/client"
	"chat-lab/infrastructure/grpc/server"
	"chat-lab/internal"
	"chat-lab/observability"
	pb "chat-lab/proto/analyzer"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"runtime"
	"sync"
	"syscall"
	"time"

	"github.com/Netflix/go-env"
	"github.com/joho/godotenv"
	grpc2 "github.com/mama165/sdk-go/grpc"
	"github.com/mama165/sdk-go/logs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func main() {
	// Configuration & Logger
	err := godotenv.Load()
	if err != nil {
		log.Fatalf("error loading .env file : %v", err)
	}

	var config internal.Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		log.Fatalf("config error: %v", err)
	}

	logger := logs.GetLoggerFromString(config.LogLevel)

	numCPU := runtime.NumCPU()
	runtime.GOMAXPROCS(numCPU)
	logger.Info("Parallelism",
		"scan_workers_nb", config.ScannerWorkerNb,
		"downloader_workers_nb", config.DownloaderWorkerNb,
		"numCPU", numCPU,
	)

	masterAddress := fmt.Sprintf("%s:%d", config.Host, config.MasterPort)
	scannerAddress := fmt.Sprintf("%s:%d", config.Host, config.ScannerPort)

	listener, err := net.Listen("tcp", scannerAddress)
	if err != nil {
		log.Fatalf("failed to listen on %s: %v", masterAddress, err)
	}

	serviceTarget := pb.FileAnalyzerService_ServiceDesc.ServiceName
	retryConfig := config.GrpcConfig.ToServiceConfig(serviceTarget)

	conn, err := grpc.NewClient(masterAddress,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultServiceConfig(retryConfig),
	)
	if err != nil {
		logger.Error("Could not setup connection to Master", "error", err)
	}
	defer conn.Close()

	grpcClient := client.NewFileAnalyzerClient(conn)

	var scanWG sync.WaitGroup
	var workersWG sync.WaitGroup

	dirChan := make(chan string, config.BufferSize)
	errChan := make(chan error, 1)

	fileDownloaderRequestChan := make(chan domain.FileDownloaderRequest, config.BufferSize)
	fileDownloaderResponseChan := make(chan domain.FileDownloaderResponse, config.BufferSize)
	fileDownloaderServer := server.NewFileDownloaderServer(logger, fileDownloaderRequestChan, fileDownloaderResponseChan)
	scannerControlService := services.NewScannerControlService(dirChan, &scanWG, logger)
	scannerControllerServer := server.NewScannerControllerServer(scannerControlService)

	s := grpc.NewServer(
		grpc.ChainUnaryInterceptor(
			grpc2.UnaryLoggingInterceptor(logger),
			server.AuthInterceptor(config.AuthenticationEnabled),
		))

	pb.RegisterFileDownloaderServiceServer(s, fileDownloaderServer)
	pb.RegisterScannerControllerServer(s, scannerControllerServer)

	// Use an error channel to capture Serve() issues asynchronously.
	go func() {
		logger.Info("Starting gRPC server", "address", scannerAddress, "at", time.Now().UTC())
		for serviceName := range s.GetServiceInfo() {
			logger.Debug("📡 gRPC exposed services", "name", serviceName)
		}
		if err := s.Serve(listener); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			errChan <- fmt.Errorf("gRPC server error: %w", err)
		}
	}()

	ctx := context.Background()
	// 2. Setup context to handle termination signals (Ctrl+C).
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	telemetryChan := make(chan event.Event, config.BufferSize)
	supervisor := workers.NewSupervisor(logger, telemetryChan, config.RestartInterval)
	fileChan := make(chan *analyzer.FileAnalyzerRequest, config.BufferSize)
	// 1. Instanciation du Manager et du Channel de métriques
	monitoringManager := observability.NewMonitoringManager(logger)
	metricsStatsChan := make(chan observability.MonitoringStats, config.BufferSize)

	// 2. Lancement du processeur de métriques (Listen)
	go monitoringManager.Listen(ctx, metricsStatsChan)

	// 2. Lancement du serveur HTTP pour l'API et le Dashboard
	go func() {
		mux := http.NewServeMux()

		// Endpoint API pour le JSON
		mux.HandleFunc("/api/stats", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.Header().Set("Access-Control-Allow-Origin", "*") // Autorise l'accès depuis d'autres ports si besoin

			stats := monitoringManager.GetLatest()
			if err := json.NewEncoder(w).Encode(stats); err != nil {
				logger.Error("❌ Erreur encodage JSON stats", "error", err)
			}
		})

		// Endpoint pour servir le dashboard HTML
		mux.HandleFunc("/", func(w http.ResponseWriter, r *http.Request) {
			// Assure-toi que dashboard_1.html est à la racine de ton projet
			http.ServeFile(w, r, "ui/monitoring.html")
		})

		serverAddr := ":8091"
		logger.Info("📊 Dashboard de monitoring disponible", "url", "http://localhost"+serverAddr)

		if err := http.ListenAndServe(serverAddr, mux); err != nil {
			logger.Error("❌ Serveur de monitoring en échec", "error", err)
		}
	}()

	telemetryWorkers, channelCapWorker :=
		buildTelemetryWorkers(config, logger, fileChan,
			dirChan, telemetryChan, monitoringManager)

	fileScannerWorkers, fileDownloaderWorker, fileSenderWorker :=
		buildFileWorkers(
			config,
			&workersWG, &scanWG, logger, monitoringManager, dirChan,
			fileChan, fileDownloaderRequestChan, fileDownloaderResponseChan, grpcClient,
			config.ChunkSizeKb, config.MaxFileSizeMb,
		)

	heartbeatWorker := workers.NewHeartbeatWorker(logger, conn, monitoringManager)

	supervisor.
		Add(telemetryWorkers, channelCapWorker).
		Add(heartbeatWorker).
		Add(fileScannerWorkers...).
		Add(fileSenderWorker).
		Add(fileDownloaderWorker...)

	go func() {
		logger.Info("Starting supervisor and all workers")
		supervisor.Run(ctx)
	}()

	// Blocking to keep binary alive
	logger.Info("🚀 Scanner Server is ready and listening for triggers... (Ctrl+C to stop)")
	<-ctx.Done()

	logger.Warn("Shutting down scanner gracefully...")

	// Stop gRPC gracefully
	s.GracefulStop()
	close(dirChan)

	// Wait for workers to empty channels
	workersWG.Wait()
	close(fileChan)

	logger.Info("Scan Summary",
		"Files", monitoringManager.FilesFound,
		"Dirs", monitoringManager.DirsScanned,
		"Bytes", monitoringManager.ScannerBytes,
		"Errors", monitoringManager.ErrorCount,
		"Skipped", monitoringManager.SkippedItem,
	)
}

func buildTelemetryWorkers(
	config internal.Config,
	logger *slog.Logger,
	fileChan chan *analyzer.FileAnalyzerRequest,
	dirChan chan string,
	telemetryChan chan event.Event,
	monitoring *observability.MonitoringManager,
) (contract.Worker, contract.Worker) {
	channelCapacityHandler := event.NewChannelCapacityHandler(
		logger, config.LowCapacityThreshold,
		monitoring,
	)
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
	monitoring *observability.MonitoringManager,
	dirChan chan string, fileChan chan *analyzer.FileAnalyzerRequest,
	requestChan chan domain.FileDownloaderRequest,
	responseChan chan domain.FileDownloaderResponse,
	client client.FileAnalyzerClient,
	chunkSizeKb, maxFileSizeMb int) ([]contract.Worker, []contract.Worker, contract.Worker) {

	var allFileScannerWorkers = make([]contract.Worker, 0, config.ScannerWorkerNb)
	for i := 0; i < config.ScannerWorkerNb; i++ {
		workersWG.Add(1)
		allFileScannerWorkers = append(allFileScannerWorkers,
			workers.NewFileScannerWorker(
				logger, config.DriveID, monitoring,
				dirChan, fileChan,
				scanWG,
				workersWG,
				config.ScannerBackpressureLowThreshold,
				config.ScannerBackpressureHardThreshold,
			),
		)
	}

	var allFileDownloaderWorkers = make([]contract.Worker, 0, config.DownloaderWorkerNb)
	for i := 0; i < config.DownloaderWorkerNb; i++ {
		allFileDownloaderWorkers = append(allFileDownloaderWorkers,
			workers.NewFileDownloaderScannerWorker(
				logger,
				requestChan,
				responseChan,
				chunkSizeKb,
				maxFileSizeMb,
			))
	}

	fileSenderWorker := workers.NewFileSenderWorker(client, logger, monitoring, fileChan, config.ProgressLogInterval)

	return allFileScannerWorkers, allFileDownloaderWorkers, fileSenderWorker
}
