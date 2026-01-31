package runtime_test

import (
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/mocks"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"context"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"go.uber.org/mock/gomock"
)

func TestOrchestrator_LoadTest(t *testing.T) {
	// 1. Setup minimaliste (on mock le repo pour ne pas être bridé par le disque/Badger)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	ctrl := gomock.NewController(t)
	mockMessageRepo := mocks.NewMockIMessageRepository(ctrl)
	mockAnalysisRepository := mocks.NewMockIAnalysisRepository(ctrl)
	mockFileTaskRepository := mocks.NewMockIFileTaskRepository(ctrl)
	mockMessageRepo.EXPECT().StoreMessage(gomock.Any()).Do(
		func(_ storage.DiskMessage) {
			time.Sleep(2 * time.Millisecond)
		},
	).Return(nil).AnyTimes()

	mockAnalysisRepository.EXPECT().Store(gomock.Any()).Return(nil).AnyTimes()

	telemetryChan := make(chan event.Event, 5000)
	fileAnalyzeChan := make(chan event.Event, 5000)
	log := slog.New(slog.DiscardHandler) // On désactive les logs pour la perf

	supervisor := workers.NewSupervisor(log, telemetryChan, 100*time.Millisecond)
	registry := runtime.NewRegistry()

	manager := runtime.NewCoordinator(log)

	o := runtime.NewOrchestrator(
		log, supervisor, registry, telemetryChan, fileAnalyzeChan,
		mockMessageRepo,
		mockAnalysisRepository,
		mockFileTaskRepository,
		manager,
		2,                    // numWorkers
		1000,                 // bufferSize
		100*time.Millisecond, // sinkTimeout
		500*time.Second,      // buffer timeout
		500*time.Millisecond, // Specialist timeout
		50*time.Millisecond,  // metric interval
		50*time.Millisecond,  // latency threshold
		50*time.Millisecond,  // wait and fail
		'*',
		800,
		500,
		0.4, 0.6,
		100,
	)
	go func() {
		if err := o.Start(ctx); err != nil {
			fmt.Printf("Orchestrator failed to start: %v\n", err)
		}
	}()
	time.Sleep(100 * time.Millisecond) // Laisse le temps aux workers de démarrer

	// 2. Variables de mesure
	var successCount atomic.Uint64
	var failureCount atomic.Uint64

	numClients := 100
	messagesPerClient := 200

	start := time.Now()
	var wg sync.WaitGroup

	// 3. Simulation du trafic
	for i := 0; i < numClients; i++ {
		wg.Add(1)
		go func(clientID int) {
			defer wg.Done()
			for j := 0; j < messagesPerClient; j++ {
				cmd := chat.PostMessageCommand{
					Room:      1,
					UserID:    fmt.Sprintf("user-%d", clientID),
					Content:   "Ceci est un message de test de charge",
					CreatedAt: time.Now().UTC(),
				}

				if err := o.PostMessage(ctx, cmd); err != nil {
					failureCount.Add(1)
				} else {
					successCount.Add(1)
				}
			}
		}(i)
	}

	wg.Wait()
	duration := time.Since(start)

	// 4. Résultats
	fmt.Printf("\n--- RÉSULTATS DU STRESS TEST ---\n")
	fmt.Printf("Durée totale     : %v\n", duration)
	fmt.Printf("Messages réussis : %d\n", successCount.Load())
	fmt.Printf("Messages rejetés : %d (Backpressure)\n", failureCount.Load())
	fmt.Printf("Débit (TPS)      : %.2f msg/sec\n", float64(successCount.Load())/duration.Seconds())
	fmt.Printf("--------------------------------\n")
}
