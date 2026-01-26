package main

import (
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/domain/specialist"
	"chat-lab/infrastructure/grpc/server"
	"chat-lab/infrastructure/storage"
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
	"log/slog"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/mama165/sdk-go/database"
	"github.com/samber/lo"
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
		fmt.Fprintf(os.Stderr, "Chat-Lab terminated with error: %v\n", err)
	}
	os.Exit(code)
}

// run initializes all components, manages the server lifecycle, and centralizes error reporting.
// This pattern is preferred over calling os.Exit or panic directly because:
// 1. It ensures all 'defer' statements (like database cleanup) are executed before the program exits.
// 2. It improves testability by decoupling the initialization logic from the main entry point.
// 3. It provides a structured way to handle graceful shutdowns for gRPC and background workers.
func run() (int, error) {
	// 1. Configuration & Logger
	var config Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		return exitConfig, fmt.Errorf("config error: %w", err)
	}

	charReplacement, err := characterRune(config.CharReplacement)
	if err != nil {
		return exitConfig, err
	}

	log := logs.GetLoggerFromString(config.LogLevel)

	ctx := context.Background()

	// 2. Database (BadgerDB)
	options := buildBadgerOpts(config, log, ctx)

	db, err := badger.Open(options)
	if err != nil {
		return exitRuntime, fmt.Errorf("database opening failed: %w", err)
	}

	if log.Enabled(ctx, slog.LevelDebug) {
		debugPort := 8081
		url := fmt.Sprintf("http://localhost:%d/inspect", debugPort)
		log.Info("Debug Badger inspector available", "url", url)
		database.StartDebugServer(db, debugPort, AnalysisMapper)
	}

	defer func() {
		// Defer ensures the database lock is released and buffers are flushed before the function returns.
		log.Info("Closing BadgerDB...")
		_ = db.Close()
	}()

	blugeCfg := bluge.DefaultConfig(config.BlugeFilepath)
	blugeWriter, err := bluge.OpenWriter(blugeCfg)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to open bluge writer: %w", err)
	}
	defer func() {
		log.Info("Closing Bluge...")
		_ = blugeWriter.Close()
	}()

	// 2.bis Sidecar Specialists Initialization
	// We launch the specialized binaries (Toxicity, Sentiment, Business...) before the engine starts.
	// This ensures the Orchestrator has all its analysis "organs" ready.
	// We use a dedicated timeout to prevent the Master from hanging if a binary
	// fails to load its resources (like ML models) or if a port is already in use.

	/*	specialistConfigs := []specialist.Config{
		{ID: specialist.MetricToxicity, BinPath: config.ToxicityBinPath, Host: config.Host, Port: config.ToxicityPort},
		{ID: specialist.MetricSentiment, BinPath: config.SentimentBinPath, Host: config.Host, Port: config.SentimentPort},
	}*/

	specialistConfigs := []specialist.Config{
		{
			ID:           specialist.MetricPDF,
			BinPath:      "./services/pdf_specialist.py",
			Host:         "localhost",
			Port:         50055, // 👈 Change ici (au lieu de 50051)
			Capabilities: []mimetypes.MIME{mimetypes.ApplicationPDF},
		},
		{
			ID:      "audio-transcriber",
			BinPath: "./services/audio_specialist.py",
			Host:    "localhost",
			Port:    50056, // 👈 Change ici (au lieu de 50052 pour être large)
			Capabilities: []mimetypes.MIME{
				mimetypes.AudioMPEG,
				mimetypes.AudioWAV,
				mimetypes.AudioXAIFF,
			},
		},
	}
	coordinator := runtime.NewCoordinator(log)

	bootCtx, cancelBoot := context.WithTimeout(ctx, config.MaxSpecialistBootDuration)
	defer cancelBoot()

	log.Info(fmt.Sprintf("Launching %d sidecar specialists...", len(specialistConfigs)))
	if err := coordinator.Init(bootCtx, specialistConfigs, config.LogLevel); err != nil {
		return exitRuntime, fmt.Errorf("specialist init failed: %w", err)
	}

	// 3. Setup Supervision & Orchestration
	telemetryChan := make(chan event.Event, config.BufferSize)
	eventChan := make(chan event.Event, config.BufferSize)
	sup := workers.NewSupervisor(log, telemetryChan, config.RestartInterval)
	registry := runtime.NewRegistry()
	messageRepository := storage.NewMessageRepository(db, log, config.LimitMessages)
	analysisRepository := storage.NewAnalysisRepository(db, blugeWriter, log, lo.ToPtr(50), 50)
	userRepository := storage.NewUserRepository(db)

	orchestrator := runtime.NewOrchestrator(
		log, sup, registry, telemetryChan, eventChan,
		messageRepository,
		analysisRepository,
		coordinator,
		config.NumberOfWorkers, config.BufferSize,
		config.SinkTimeout, config.MetricInterval, config.LatencyThreshold, config.IngestionTimeout,
		charReplacement,
		config.LowCapacityThreshold,
		config.MaxContentLength,
		config.MinScoring, config.MaxScoring,
		config.MaxAnalyzedEvent,
	)

	// 4. Context & Signals
	// NotifyContext captures OS signals and cancels the context to trigger a shutdown.
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Error (gRPC & Orchestrator)
	errChan := make(chan error, 2)

	// 5. Start the Engine (Workers and Fanout)
	go func() {
		log.Info("Starting orchestrator...")
		if err := orchestrator.Start(ctx); err != nil {
			errChan <- fmt.Errorf("orchestrator error: %w", err)
		}
	}()

	// 6. gRPC Server Setup
	address := fmt.Sprintf("%s:%d", config.Host, config.Port)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to listen on %s: %w", address, err)
	}

	s := grpc.NewServer(
		grpc.ChainUnaryInterceptor(
			grpc3.UnaryLoggingInterceptor(log),
			server.AuthInterceptor,
		))
	chatService := services.NewChatService(orchestrator)
	authService := services.NewAuthService(userRepository, config.AuthTokenDuration)
	counter := analyzer.NewCountAnalyzedFiles()
	analyzerService := services.NewAnalyzerService(log, analysisRepository, eventChan, &counter)
	chatServer := server.NewChatServer(log, chatService, config.ConnectionBufferSize, config.DeliveryTimeout)
	fileAnalyzerServer := server.NewFileAnalyzerServer(analyzerService, log, &counter)
	authServer := server.NewAuthServer(authService)
	pb1.RegisterChatServiceServer(s, chatServer)
	pb2.RegisterAuthServiceServer(s, authServer)
	pb3.RegisterFileAnalyzerServiceServer(s, fileAnalyzerServer)

	// Use an error channel to capture Serve() issues asynchronously.
	go func() {
		log.Info("Starting gRPC server", "address", address, "at", time.Now().UTC())
		for serviceName := range s.GetServiceInfo() {
			log.Info("📡 Service réellement exposé", "name", serviceName)
		}
		if err := s.Serve(listener); err != nil && !errors.Is(err, grpc.ErrServerStopped) {
			errChan <- fmt.Errorf("gRPC server error: %w", err)
		}
	}()

	// 7. Wait for Stop or Error
	// The execution blocks here until either a signal is received or the server crashes.
	select {
	case <-ctx.Done():
		log.Info("Shutdown signal received")
	case err := <-errChan:
		return exitRuntime, err
	}

	// 8. Final Cleanup (Graceful Shutdown)
	// We allow active gRPC streams to finish and workers to drain their channels.
	log.Info("Shutting down gracefully...")
	s.GracefulStop()
	orchestrator.Stop()
	log.Info("Program stopped cleanly")

	return exitOK, nil
}

func buildBadgerOpts(config Config, log *slog.Logger, ctx context.Context) badger.Options {
	options := badger.DefaultOptions(config.BadgerFilepath)

	if log.Enabled(ctx, slog.LevelDebug) {
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

	row.Type = "FILE"
	if _, ok := analysis.Payload.(storage.AudioDetails); ok {
		row.Type = "AUDIO"
	}

	row.Detail = analysis.Summary

	scores := ""
	for k, v := range analysis.Scores {
		scores += fmt.Sprintf("%s:%.2f ", k, v)
	}
	row.Scores = scores

	return row
}
