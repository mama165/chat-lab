package main

import (
	"chat-lab/repositories"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"context"
	"github.com/Netflix/go-env"
	"github.com/dgraph-io/badger/v4"
	"github.com/mama165/sdk-go/logs"
	"os"
	"os/signal"
	"syscall"
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

	sup := workers.NewSupervisor(log)
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
	err = orchestrator.Start(ctx)
	if err != nil {
		log.Error(err.Error())
	}

	<-ctx.Done() // Wait for Ctrl+C

	// 6. Graceful stop
	orchestrator.Stop()
}
