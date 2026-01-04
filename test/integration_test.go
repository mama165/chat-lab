package test

import (
	"chat-lab/domain"
	"chat-lab/mocks"
	"chat-lab/repositories"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/storage"
	"context"
	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/mama165/sdk-go/logs"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
	"log/slog"
	"testing"
	"time"
)

func Test_Scenario(t *testing.T) {
	ctx := context.Background()
	req := require.New(t)
	// Reduced to 16 Mo for testing (avoid 20 Go of storage)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)

	// 1. On crée un canal pour signaler la fin du traitement
	done := make(chan struct{})
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	supervisor := workers.NewSupervisor(log)
	registry := runtime.NewRegistry()
	messageRepository := repositories.NewMessageRepository(db, log, lo.ToPtr(100))
	orchestrator := runtime.NewOrchestrator(
		log, supervisor, registry, messageRepository,
		10, 1000, 3*time.Second,
	)
	ctrl := gomock.NewController(t)
	mockMessageRepository := mocks.NewMockRepository(ctrl)
	mockMessageRepository.EXPECT().
		StoreMessage(gomock.Any()).
		Do(func(msg any) {
			close(done) // On signale qu'on a reçu le message
		}).
		Return(nil).
		Times(1) // On attend exactement 1 appel

	mockTimelineSink := mocks.NewMockEventSink(ctrl)
	mockTimelineSink.EXPECT().Consume(gomock.Any(), gomock.Any()).Return(nil).Times(1)
	diskSink := storage.NewDiskSink(mockMessageRepository, log)
	orchestrator.RegisterSinks(mockTimelineSink, diskSink)
	id := 1
	room := domain.NewRoom(id)
	orchestrator.RegisterRoom(room)

	go func() {
		err = orchestrator.Start(ctx)
		req.NoError(err)
	}()

	// Clean everything at the end of the test
	t.Cleanup(func() {
		orchestrator.Stop()
		db.Close()
	})

	userID := uuid.NewString()
	content := "this message will self destruct in 5 seconds"
	at := time.Now().UTC()

	// When a cmd message is posted
	orchestrator.Dispatch(domain.PostMessageCommand{
		Room:      id,
		UserID:    userID,
		Content:   content,
		CreatedAt: at,
	})

	// And wait time for channels & goroutines
	select {
	case <-done:
		// Then the message has reached the repository
	case <-time.After(2 * time.Second):
		t.Fatal("Timeout: message has never reached the repository")
	}

	// Then supervisor can be canceled
	req.NotNil(supervisor.Cancel)
}
