package main

import (
	grpc2 "chat-lab/grpc"
	v1 "chat-lab/proto/chat"
	"chat-lab/repositories"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"context"
	"fmt"
	"github.com/Netflix/go-env"
	"github.com/dgraph-io/badger/v4"
	"github.com/mama165/sdk-go/logs"
	"google.golang.org/grpc"
	"net"
	"os"
	"os/signal"
	"syscall"
	"time"
)

func main() {
	var config Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		panic(err)
	}
	log := logs.GetLoggerFromString(config.LogLevel)
	db, err := badger.Open(badger.DefaultOptions(config.BadgerFilepath).
		WithLoggingLevel(badger.INFO))
	if err != nil {
		panic(err)
	}
	defer db.Close()

	sup := workers.NewSupervisor(log, config.RestartInterval)
	registry := runtime.NewRegistry()

	messageRepository := repositories.NewMessageRepository(db, log, config.LimitMessages)
	orchestrator := runtime.NewOrchestrator(
		log, sup, registry, messageRepository,
		config.NumberOfWorkers, config.BufferSize, config.SinkTimeout,
	)
	ctx := context.Background()
	ctx, stop := signal.NotifyContext(ctx,
		os.Interrupt,    // Sent by Ctrl+C
		syscall.SIGTERM, // Sent by Docker/Kubernetes/systemd
	)
	defer stop()

	// 5. Start the engine
	if err = orchestrator.Start(ctx); err != nil {
		panic(err)
	}

	// 6. Start gRPC Server now that orchestrator is ready
	address := fmt.Sprintf("%s:%d", config.Host, config.Port)
	listener, err := net.Listen("tcp", address)
	if err != nil {
		panic("error building server: " + err.Error())
	}

	s := grpc.NewServer()
	server := grpc2.NewChatServer(log, orchestrator, config.ConnectionBufferSize)
	v1.RegisterChatServiceServer(s, server)

	// Running the blocking gRPC server in a goroutine
	go func() {
		log.Info("Starting gRPC server", "address", address, "at", time.Now().UTC())
		if err = s.Serve(listener); err != nil && err != grpc.ErrServerStopped {
			log.Error("gRPC server error", "error", err)
			os.Exit(1)
		}
	}()

	<-ctx.Done() // Wait for Ctrl+C
	log.Info("Shutting down gracefully...")

	// 7. Graceful stop
	s.GracefulStop()
	orchestrator.Stop()
	log.Info("Program stopped")
}
