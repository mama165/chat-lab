package main

import (
	"chat-lab/auth"
	"chat-lab/domain/event"
	grpc2 "chat-lab/grpc"
	pb2 "chat-lab/proto/account"
	pb "chat-lab/proto/chat"
	"chat-lab/repositories"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"errors"
	"fmt"
	grpc3 "github.com/mama165/sdk-go/grpc"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"

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

	charReplacement, err := config.CharacterRune()
	if err != nil {
		return exitRuntime, err
	}

	log := logs.GetLoggerFromString(config.LogLevel)

	// 2. Database (BadgerDB)
	db, err := badger.Open(badger.DefaultOptions(config.BadgerFilepath).
		WithLoggingLevel(badger.INFO))
	if err != nil {
		return exitRuntime, fmt.Errorf("database opening failed: %w", err)
	}
	// Defer ensures the database lock is released and buffers are flushed before the function returns.
	defer func() {
		log.Info("Closing BadgerDB...")
		_ = db.Close()
	}()

	// 3. Setup Supervision & Orchestration
	telemetryChan := make(chan event.Event, config.BufferSize)
	sup := workers.NewSupervisor(log, telemetryChan, config.RestartInterval)
	registry := runtime.NewRegistry()
	messageRepository := repositories.NewMessageRepository(db, log, config.LimitMessages)

	orchestrator := runtime.NewOrchestrator(
		log, sup, registry, telemetryChan, messageRepository,
		config.NumberOfWorkers, config.BufferSize,
		config.SinkTimeout, config.MetricInterval, config.LatencyThreshold, config.IngestionTimeout,
		charReplacement,
		config.LowCapacityThreshold,
		config.MaxContentLength,
	)

	// 4. Context & Signals
	// NotifyContext captures OS signals and cancels the context to trigger a shutdown.
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// 5. Start the Engine (Workers and Fanout)
	if err = orchestrator.Start(ctx); err != nil {
		return exitRuntime, fmt.Errorf("orchestrator failed to start: %w", err)
	}

	// 6. gRPC Server Setup
	address := fmt.Sprintf("%s:%d", config.Host, config.Port)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to listen on %s: %w", address, err)
	}

	s := grpc.NewServer(
		grpc.ChainUnaryInterceptor(
			grpc3.UnaryLoggingInterceptor(log),
			auth.AuthInterceptor,
		))
	chatService := services.NewChatService(orchestrator)
	userRepository := repositories.NewUserRepository(db)
	authService := services.NewAuthService(userRepository, config.AuthTokenDuration)
	chatServer := grpc2.NewChatServer(log, chatService, config.ConnectionBufferSize, config.DeliveryTimeout)
	authServer := grpc2.NewAuthServer(authService)
	pb.RegisterChatServiceServer(s, chatServer)
	pb2.RegisterAuthServiceServer(s, authServer)

	// Use an error channel to capture Serve() issues asynchronously.
	errChan := make(chan error, 1)
	go func() {
		log.Info("Starting gRPC server", "address", address, "at", time.Now().UTC())
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
