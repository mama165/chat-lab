package main

import (
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"chat-lab/infrastructure/web"
	"chat-lab/internal"
	"chat-lab/observability"
	pb4 "chat-lab/proto/storage"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"errors"
	"flag"
	"fmt"
	"io"
	"log/slog"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strconv"
	"syscall"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/joho/godotenv"
	"github.com/samber/lo"
	"google.golang.org/protobuf/proto"

	"github.com/Netflix/go-env"
	"github.com/dgraph-io/badger/v4"
	"github.com/mama165/sdk-go/logs"
)

// Exit codes to provide meaningful status to the operating system or service manager (e.g., systemd).
const (
	exitOK      = 0
	exitRuntime = 1
	exitConfig  = 2
)

// shutdownTimeout bounds how long the program waits for its workers to stop.
const shutdownTimeout = 10 * time.Second

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

	// The folder to scan comes from SCAN_ROOT_DIR; the -dir flag overrides it.
	scanDir := flag.String("dir", config.ScanRootDir, "folder to scan (overrides SCAN_ROOT_DIR)")
	flag.Parse()
	config.ScanRootDir = *scanDir
	if config.ScanRootDir == "" {
		return exitConfig, errors.New("no folder to scan: set SCAN_ROOT_DIR or pass -dir <folder>")
	}
	if info, err := os.Stat(config.ScanRootDir); err != nil || !info.IsDir() {
		return exitConfig, fmt.Errorf("folder to scan %q is not an accessible directory", config.ScanRootDir)
	}

	// 2. On prépare le fichier de log
	logFile, err := os.OpenFile("master_execution.log", os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0666)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to open log file: %w", err)
	}
	defer logFile.Close()

	// 3. On crée un MultiWriter (Console + Fichier)
	multiWriter := io.MultiWriter(os.Stdout, logFile)

	// 4. On crée un NOUVEAU handler qui utilise le niveau de ton baseLogger
	// mais qui écrit vers notre multiWriter
	level := logs.GetLevelFromString(config.LogLevel)

	handler := slog.NewTextHandler(multiWriter, &slog.HandlerOptions{
		Level: level,
	})

	// 5. On instancie le logger final
	logger := slog.New(handler)

	// CRITIQUE : On définit ce logger comme défaut global
	// Ainsi, ton Coordinator et ton Orchestrator l'utiliseront automatiquement
	slog.SetDefault(logger)

	// Context & Signals
	// NotifyContext captures OS signals and cancels the context to trigger a shutdown.
	// It is created first so that the specialists, started below, are stopped with it.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// Database (BadgerDB)
	options := buildBadgerOpts(config, logger, ctx)

	db, err := badger.Open(options)
	if err != nil {
		return exitRuntime, fmt.Errorf("database opening failed: %w", err)
	}

	monitor := domain.NewGlobalMonitoring()

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
	// We launch the specialized binaries (PDF, audio, image) before the engine starts.
	// This ensures the Orchestrator has all its analysis "organs" ready.
	// We use a dedicated timeout to prevent the Master from hanging if a binary
	// fails to load its resources (like ML models) or if a port is already in use.

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
	sup := workers.NewSupervisor(logger, telemetryChan, config.RestartInterval)
	analysisRepository := storage.NewAnalysisRepository(db, blugeWriter, logger, lo.ToPtr(50), 50)
	coordinator := runtime.NewCoordinator(logger, processTrackerChan, config.MaxFileSizeMb)
	monitoringManager := observability.NewMonitoringManager(logger)

	bootCtx, cancelBoot := context.WithTimeout(ctx, config.MaxSpecialistBootDuration)
	defer cancelBoot()

	if config.EnableSpecialists {
		logger.Info(fmt.Sprintf("Launching %d sidecar specialists...", len(specialistConfigs)))
		if err := coordinator.Init(ctx, bootCtx, specialistConfigs, config.LogLevel, config.GrpcConfig); err != nil {
			return exitRuntime, fmt.Errorf("specialist init failed: %w", err)
		}
	}

	counter := analyzer.NewCountAnalyzedFiles()
	analyzerService := services.NewAnalyzerService(logger, analysisRepository, eventChan, &counter)

	orchestrator := runtime.NewOrchestrator(
		logger, config, monitor, sup,
		telemetryChan, eventChan,
		processTrackerChan,
		analysisRepository,
		coordinator,
		analyzerService,
		monitoringManager,
	)

	// Error (monitoring server & orchestrator)
	errChan := make(chan error, 2)

	// Start the engine: scan, storage, analysis and telemetry workers.
	orchestratorDone := make(chan struct{})
	go func() {
		defer close(orchestratorDone)
		logger.Info("Starting orchestrator...")
		if err := orchestrator.Start(ctx); err != nil {
			errChan <- fmt.Errorf("orchestrator error: %w", err)
		}
	}()

	// Search API, monitoring API and dashboard pages
	httpServer := web.NewServer(monitor, monitoringManager, analysisRepository)
	httpAddr := net.JoinHostPort(config.MonitoringHost, strconv.Itoa(config.MonitoringPort))
	go func() {
		logger.Info("🔎 Search and monitoring available",
			"search", fmt.Sprintf("http://%s/api/search?q=...", httpAddr),
			"monitoring", fmt.Sprintf("http://%s/monitoring.html", httpAddr))
		if err := httpServer.Start(httpAddr); err != nil && !errors.Is(err, http.ErrServerClosed) {
			logger.Error("HTTP server failed", "err", err)
		}
	}()

	// Wait for Stop or Error
	// The execution blocks here until either a signal is received or the engine fails.
	select {
	case <-ctx.Done():
		logger.Info("Shutdown signal received")
	case err := <-errChan:
		return exitRuntime, err
	}

	// Final Cleanup (Graceful Shutdown)
	// Wait for the workers to exit before the deferred database closes run.
	logger.Info("Shutting down gracefully...")
	orchestrator.Stop()
	select {
	case <-orchestratorDone:
	case <-time.After(shutdownTimeout):
		logger.Warn("Some workers did not stop in time", "timeout", shutdownTimeout)
	}
	logger.Info("Program stopped cleanly")

	return exitOK, nil
}

func buildBadgerOpts(config internal.Config, logger *slog.Logger, ctx context.Context) badger.Options {
	options := badger.DefaultOptions(config.BadgerFilepath)

	options.IndexCacheSize = 100 << 20 // Optional : 100MB cache
	options.DetectConflicts = false    // Detect & repair conflicts

	options = options.WithLoggingLevel(badger.INFO)

	if logger.Enabled(ctx, slog.LevelDebug) {
		options = options.WithBypassLockGuard(true)
	}

	return options
}

func AnalysisMapper(key string, val []byte) internal.InspectRow {
	row := internal.DefaultMapper(key, val)

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
			}

		// Cas 3 : Texte brut
		case storage.TextContent, *storage.TextContent:
			row.Type = "TEXT"
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
