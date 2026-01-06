package main

import (
	grpc2 "chat-lab/grpc"
	v1 "chat-lab/proto/chat"
	"chat-lab/repositories"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"context"
	"fmt"
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

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "Fatal error: %v\n", err)
		os.Exit(1)
	}
}

// run initializes all components, manages the server lifecycle, and centralizes error reporting.
// This pattern is preferred over calling os.Exit or panic directly because:
// 1. It ensures all 'defer' statements (like database cleanup) are executed before the program exits.
// 2. It improves testability by decoupling the initialization logic from the main entry point.
// 3. It provides a structured way to handle graceful shutdowns for gRPC and background workers.
func run() error {
	// 1. Configuration & Logger
	var config Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		return fmt.Errorf("config error: %w", err)
	}
	log := logs.GetLoggerFromString(config.LogLevel)

	// 2. Database (BadgerDB)
	db, err := badger.Open(badger.DefaultOptions(config.BadgerFilepath).
		WithLoggingLevel(badger.INFO))
	if err != nil {
		return fmt.Errorf("database opening failed: %w", err)
	}
	//  Defer will be executed before run() returned anything to main()
	defer func() {
		log.Info("Closing BadgerDB...")
		_ = db.Close()
	}()

	// 3. Setup Supervision & Orchestration
	sup := workers.NewSupervisor(log, config.RestartInterval)
	registry := runtime.NewRegistry()
	messageRepository := repositories.NewMessageRepository(db, log, config.LimitMessages)

	orchestrator := runtime.NewOrchestrator(
		log, sup, registry, messageRepository,
		config.NumberOfWorkers, config.BufferSize, config.SinkTimeout,
	)

	// 4. Context & Signals
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// 5. Start the Engine
	if err = orchestrator.Start(ctx); err != nil {
		return fmt.Errorf("orchestrator failed to start: %w", err)
	}

	// 6. gRPC Server Setup
	address := fmt.Sprintf("%s:%d", config.Host, config.Port)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		return fmt.Errorf("failed to listen on %s: %w", address, err)
	}

	s := grpc.NewServer()
	server := grpc2.NewChatServer(log, orchestrator, config.ConnectionBufferSize)
	v1.RegisterChatServiceServer(s, server)

	// Use an error channel to capture Serve() issues
	errChan := make(chan error, 1)
	go func() {
		log.Info("Starting gRPC server", "address", address, "at", time.Now().UTC())
		if err := s.Serve(listener); err != nil && err != grpc.ErrServerStopped {
			errChan <- fmt.Errorf("gRPC server error: %w", err)
		}
	}()

	// 7. Wait for Stop or Error
	select {
	case <-ctx.Done():
		log.Info("Shutting down gracefully...")
	case err := <-errChan:
		return err
	}

	// 8. Final Cleanup
	s.GracefulStop()
	orchestrator.Stop()
	log.Info("Program stopped cleanly")

	return nil
}
