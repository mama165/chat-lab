package main

import (
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/grpc/server"
	"chat-lab/infrastructure/storage"
	"chat-lab/internal"
	pb2 "chat-lab/proto/account"
	pb3 "chat-lab/proto/analyzer"
	pb1 "chat-lab/proto/chat"
	pb4 "chat-lab/proto/storage"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"errors"
	"fmt"
	"log"
	"log/slog"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/joho/godotenv"
	"github.com/mama165/sdk-go/database"
	"github.com/samber/lo"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/proto"

	grpc3 "github.com/mama165/sdk-go/grpc"

	"github.com/Netflix/go-env"
	"github.com/dgraph-io/badger/v4"
	"github.com/mama165/sdk-go/logs"
	"google.golang.org/grpc"
)

// Exit codes to provide meaningful status to the operating system or service manager (e.g., systemd).
const (
	exitOK      = 0
	exitRuntime = 1
	exitConfig  = 2
)

func main() {
	// The main function acts as a thin wrapper.
	// Its only responsibility is to call run() and handle the OS exit code.
	code, err := run()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Master terminated with error: %v\n", err)
	}
	os.Exit(code)
}

// run initializes all components, manages the server lifecycle, and centralizes error reporting.
// This pattern is preferred over calling os.Exit or panic directly because:
// 1. It ensures all 'defer' statements (like database cleanup) are executed before the program exits.
// 2. It improves testability by decoupling the initialization logic from the main entry point.
// 3. It provides a structured way to handle graceful shutdowns for gRPC and background workers.
func run() (int, error) {
	// Configuration & Logger
	err := godotenv.Load()
	if err != nil {
		return exitConfig, fmt.Errorf("error loading .env file : %w", err)
	}

	var config internal.Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		return exitConfig, fmt.Errorf("config error: %w", err)
	}

	charReplacement, err := internal.CharacterRune(config.CharReplacement)
	if err != nil {
		return exitConfig, err
	}

	logger := logs.GetLoggerFromString(config.LogLevel)

	ctx := context.Background()

	// Database (BadgerDB)
	options := buildBadgerOpts(config, logger, ctx)

	db, err := badger.Open(options)
	if err != nil {
		return exitRuntime, fmt.Errorf("database opening failed: %w", err)
	}

	if logger.Enabled(ctx, slog.LevelDebug) {
		debugPort := config.DebugPort
		endpoint := "/inspect"
		url := fmt.Sprintf("http://localhost:%d%s", debugPort, endpoint)
		logger.Info("Debug Badger inspector available", "url", url)
		database.StartDebugServer(db, debugPort, endpoint, AnalysisMapper)
	}

	defer func() {
		// Defer ensures the database lock is released and buffers are flushed before the function returns.
		logger.Info("Closing BadgerDB...")
		_ = db.Close()
	}()

	blugeCfg := bluge.DefaultConfig(config.BlugeFilepath)
	blugeWriter, err := bluge.OpenWriter(blugeCfg)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to open bluge writer: %w", err)
	}
	defer func() {
		logger.Info("Closing Bluge...")
		_ = blugeWriter.Close()
	}()

	// 2.bis Sidecar Specialists Initialization
	// We launch the specialized binaries (Toxicity, Sentiment, Business...) before the engine starts.
	// This ensures the Orchestrator has all its analysis "organs" ready.
	// We use a dedicated timeout to prevent the Master from hanging if a binary
	// fails to load its resources (like ML models) or if a port is already in use.

	/*	specialistConfigs := []specialist.Config{
		{ID: specialist.MetricToxicity, BinPath: config.ToxicityBinPath, Host: config.Host, MasterPort: config.ToxicityPort},
		{ID: specialist.MetricSentiment, BinPath: config.SentimentBinPath, Host: config.Host, MasterPort: config.SentimentPort},
	}*/

	specialistConfigs := []domain.Config{
		{
			ID:           domain.MetricPDF,
			BinPath:      "./services/pdf_specialist.py",
			Host:         "localhost",
			Port:         50055,
			Capabilities: []mimetypes.MIME{mimetypes.ApplicationPDF},
		},
		{
			ID:      domain.MetricAudio,
			BinPath: "./services/audio_specialist.py",
			Host:    "localhost",
			Port:    50056,
			Capabilities: []mimetypes.MIME{
				mimetypes.AudioMPEG,
				mimetypes.AudioWAV,
				mimetypes.AudioXAIFF,
			},
		},
		{
			ID:      domain.MetricImage,
			BinPath: "./services/image_specialist.py",
			Host:    "localhost",
			Port:    50057,
			Capabilities: []mimetypes.MIME{
				mimetypes.ImagePNG,
				mimetypes.ImageJPEG,
				mimetypes.ImageGIF,
			},
		},
	}
	// Setup Supervision & Orchestration
	telemetryChan := make(chan event.Event, config.BufferSize)
	processTrackerChan := make(chan domain.Process, config.BufferSize)
	eventChan := make(chan event.Event, config.BufferSize)
	fileDownloaderRequestChan := make(chan domain.FileDownloaderRequest, config.BufferSize)
	tmpFilePath := make(chan string, config.BufferSize)
	sup := workers.NewSupervisor(logger, telemetryChan, config.RestartInterval)
	registry := runtime.NewRegistry()
	messageRepository := storage.NewMessageRepository(db, logger, config.LimitMessages)
	analysisRepository := storage.NewAnalysisRepository(db, blugeWriter, logger, lo.ToPtr(50), 50)
	fileTaskRepository := storage.NewFileTaskRepository(db, logger)
	userRepository := storage.NewUserRepository(db)
	coordinator := runtime.NewCoordinator(logger, processTrackerChan, tmpFilePath, config.MaxFileSizeMb)

	bootCtx, cancelBoot := context.WithTimeout(ctx, config.MaxSpecialistBootDuration)
	defer cancelBoot()

	if config.EnableSpecialists {
		logger.Info(fmt.Sprintf("Launching %d sidecar specialists...", len(specialistConfigs)))
		if err := coordinator.Init(ctx, bootCtx, specialistConfigs, config.LogLevel, config.GrpcConfig); err != nil {
			return exitRuntime, fmt.Errorf("specialist init failed: %w", err)
		}
	}

	// gRPC Server Setup
	masterAddress := fmt.Sprintf("%s:%d", config.Host, config.MasterPort)
	scannerAddress := fmt.Sprintf("%s:%d", config.Host, config.ScannerPort)
	listener, err := net.Listen("tcp", masterAddress)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to listen on master %s: %w", masterAddress, err)
	}

	retryConfig := config.GrpcConfig.ToServiceConfig(pb3.FileDownloaderService_Download_FullMethodName)
	conn, err := grpc.NewClient(scannerAddress,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultServiceConfig(retryConfig),
	)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to listen on %s: %w", scannerAddress, err)
	}

	grpcClient := pb3.NewFileDownloaderServiceClient(conn)

	// Create the temp directory
	// /tmp/{directory}
	fileDownloadingDirPath := filepath.Join(os.TempDir(), config.TmpDownloadingDir)
	if err = os.MkdirAll(fileDownloadingDirPath, 0755); err != nil {
		return exitRuntime, fmt.Errorf("failed to create dir %s : %w", fileDownloadingDirPath, err)
	}
	fileAccumulator := services.NewFileAccumulator(fileDownloadingDirPath, tmpFilePath)

	orchestrator := runtime.NewOrchestrator(
		logger, sup, registry, telemetryChan, eventChan,
		processTrackerChan,
		fileDownloaderRequestChan,
		tmpFilePath,
		messageRepository,
		analysisRepository,
		fileTaskRepository,
		coordinator,
		grpcClient,
		fileAccumulator,
		config.NumberOfWorkers, config.BufferSize,
		config.SinkTimeout, config.BufferTimeout, config.SpecialistTimeout, config.MetricInterval, config.LatencyThreshold, config.IngestionTimeout,
		charReplacement,
		config.LowCapacityThreshold,
		config.MaxContentLength,
		config.MinScoring, config.MaxScoring,
		config.MaxAnalyzedEvent,
		config.FileTransferInterval,
		config.PendingFileBatchSize,
	)

	// Context & Signals
	// NotifyContext captures OS signals and cancels the context to trigger a shutdown.
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Error (gRPC & Orchestrator)
	errChan := make(chan error, 2)

	// Start the Engine (Workers and Fanout)
	go func() {
		logger.Info("Starting orchestrator...")
		if err := orchestrator.Start(ctx); err != nil {
			errChan <- fmt.Errorf("orchestrator error: %w", err)
		}
	}()

	s := grpc.NewServer(
		grpc.ChainUnaryInterceptor(
			grpc3.UnaryLoggingInterceptor(logger),
			server.AuthInterceptor(config.AuthenticationEnabled),
		))

	chatService := services.NewChatService(orchestrator)
	authService := services.NewAuthService(userRepository, config.AuthTokenDuration)
	counter := analyzer.NewCountAnalyzedFiles()
	analyzerService := services.NewAnalyzerService(logger, analysisRepository, eventChan, &counter)
	chatServer := server.NewChatServer(logger, chatService, config.ConnectionBufferSize, config.BufferTimeout)
	fileAnalyzerServer := server.NewFileAnalyzerServer(analyzerService, logger, &counter)

	authServer := server.NewAuthServer(authService)
	pb1.RegisterChatServiceServer(s, chatServer)
	pb2.RegisterAuthServiceServer(s, authServer)
	pb3.RegisterFileAnalyzerServiceServer(s, fileAnalyzerServer)

	// Use an error channel to capture Serve() issues asynchronously.
	go func() {
		logger.Info("Starting gRPC server", "address", masterAddress, "at", time.Now().UTC())
		for serviceName := range s.GetServiceInfo() {
			logger.Debug("📡 gRPC exposed services", "name", serviceName)
		}
		if err := s.Serve(listener); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			errChan <- fmt.Errorf("gRPC server error: %w", err)
		}
	}()

	// Wait for Stop or Error
	// The execution blocks here until either a signal is received or the server crashes.
	select {
	case <-ctx.Done():
		logger.Info("Shutdown signal received")
	case err := <-errChan:
		return exitRuntime, err
	}

	// Final Cleanup (Graceful Shutdown)
	// We allow active gRPC streams to finish and workers to drain their channels.
	logger.Info("Shutting down gracefully...")
	s.GracefulStop()
	orchestrator.Stop()
	logger.Info("Program stopped cleanly")

	return exitOK, nil
}

func buildBadgerOpts(config internal.Config, logger *slog.Logger, ctx context.Context) badger.Options {
	options := badger.DefaultOptions(config.BadgerFilepath)

	if logger.Enabled(ctx, slog.LevelDebug) {
		options = options.WithLoggingLevel(badger.DEBUG).
			WithBypassLockGuard(true)
	} else {
		options = options.WithLoggingLevel(badger.INFO)
	}

	return options
}

func AnalysisMapper(key string, val []byte) database.InspectRow {
	row := database.DefaultMapper(key, val)

	var p pb4.Analysis
	if err := proto.Unmarshal(val, &p); err != nil {
		row.Detail = "Error: unmarshal failed"
		return row
	}

	analysis, err := storage.ToAnalysis(&p)
	if err != nil {
		return row
	}

	// Initialisation par défaut
	row.Detail = analysis.Summary
	row.Type = "BASE"

	if analysis.Payload != nil {
		switch payload := analysis.Payload.(type) {

		// Cas 1 : C'est bien identifié comme de l'audio
		case storage.AudioDetails, *storage.AudioDetails:
			row.Type = "AUDIO"
			var transcription string

			// Extraction sécurisée (Pointeur ou Valeur)
			if a, ok := payload.(storage.AudioDetails); ok {
				transcription = a.Transcription
			} else if aPtr, ok := payload.(*storage.AudioDetails); ok {
				transcription = aPtr.Transcription
			}

			row.Detail = transcription
			log.Printf("🎤 [DEBUG] Transcription trouvée : %s", transcription)

		// Cas 2 : C'est identifié comme un fichier (cas actuel de tes logs)
		case storage.FileDetails, *storage.FileDetails:
			row.Type = "FILE"
			var content string

			if f, ok := payload.(storage.FileDetails); ok {
				content = f.Content
			} else if fPtr, ok := payload.(*storage.FileDetails); ok {
				content = fPtr.Content
			}

			// Si le contenu est vide, on garde le summary, sinon on affiche le texte
			if content != "" {
				row.Detail = content
				log.Printf("📄 [DEBUG] Texte extrait du fichier : %s", content)
			} else {
				log.Printf("📄 [DEBUG] Fichier standard sans contenu textuel")
			}

		// Cas 3 : Texte brut (Chat)
		case storage.TextContent, *storage.TextContent:
			row.Type = "CHAT"
			if t, ok := payload.(storage.TextContent); ok {
				row.Detail = t.Content
			} else if tPtr, ok := payload.(*storage.TextContent); ok {
				row.Detail = tPtr.Content
			}
		}
	}

	// Gestion des scores pour l'affichage
	scores := ""
	for k, v := range analysis.Scores {
		scores += fmt.Sprintf("%s:%.2f ", k, v)
	}
	row.Scores = scores

	return row
}
