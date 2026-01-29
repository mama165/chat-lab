package main

import (
	"chat-lab/contract"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	pb "chat-lab/proto/analyzer"
	"chat-lab/runtime/workers"
	"context"
	"flag"
	"log"
	"os"
	"os/signal"
	"runtime"
	"sync"
	"syscall"
	"time"

	"github.com/mama165/sdk-go/logs"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func main() {
	// 1. Parsing des options (root, server addr, parallel)
	rootDir := flag.String("root", ".", "target directory")
	address := flag.String("address", "localhost:8080", "gRPC listen address")
	goroutineNbr := flag.Int("parallel", runtime.NumCPU(), "number of concurrent goroutines")
	driveID := flag.String("driveID", "os-main", "disk identifier for Badger")
	flag.Parse()

	if *goroutineNbr < runtime.NumCPU() {
		runtime.GOMAXPROCS(*goroutineNbr)
	} else {
		runtime.GOMAXPROCS(runtime.NumCPU() / 2)
	}

	logger := logs.GetLoggerFromString("DEBUG")

	conn, err := grpc.Dial(*address, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Fail to connect with Master: %v", err)
	}
	defer conn.Close()

	client := pb.NewFileAnalyzerServiceClient(conn)

	ctx := context.Background()
	// 2. Setup context to handle termination signals (Ctrl+C).
	ctx, stop := signal.NotifyContext(ctx, os.Interrupt, syscall.SIGTERM)
	defer stop()

	telemetryChan := make(chan event.Event, 1000)
	supervisor := workers.NewSupervisor(logger, telemetryChan, 5*time.Second)
	counter := workers.NewCounterFileScanner()
	dirChan := make(chan string, 1000)
	fileChan := make(chan *analyzer.FileAnalyzerRequest, 1000)

	var scanWG sync.WaitGroup
	var workersWG sync.WaitGroup
	scanWG.Add(1)
	dirChan <- *rootDir

	go func() {
		// Waiting for no directories left
		scanWG.Wait()
		logger.Info("Logic: No more directories to scan. Closing dirChan.")
		close(dirChan)

		// Waiting for all scanner workers to stop
		workersWG.Wait()
		logger.Info("Technical: All scanner workers exited. Closing fileChan.")
		close(fileChan)
	}()

	var allWorkers = make([]contract.Worker, 0, *goroutineNbr+1)
	for i := 0; i < *goroutineNbr; i++ {
		workersWG.Add(1)
		allWorkers = append(allWorkers,
			workers.NewFileScannerWorker(
				logger,
				*rootDir, *driveID, counter,
				dirChan, fileChan,
				&scanWG,
				&workersWG,
			),
		)
	}
	allWorkers = append(allWorkers, workers.NewFileSenderWorker(client, logger, fileChan))
	supervisor.Add(allWorkers...)

	logger.Info("Starting supervisor and all workers")
	supervisor.Run(ctx)

	logger.Info("Scan Summary",
		"Files", counter.FilesScanned,
		"Dirs", counter.DirsScanned,
		"Bytes", counter.BytesProcessed,
		"Errors", counter.ErrorCount,
		"Skipped", counter.SkippedItems,
	)
}
