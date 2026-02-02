package test

import (
	"chat-lab/domain"
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/mocks"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/blugelabs/bluge"

	"github.com/mama165/sdk-go/database"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/mama165/sdk-go/logs"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func Test_Scenario(t *testing.T) {
	ctx := context.Background()
	req := require.New(t)
	// Reduced to 16 Mo for testing (avoid 20 Go of storage)
	db, err := badger.Open(badger.DefaultOptions(database.DefaultPath).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)

	blugeCfg := bluge.DefaultConfig(database.DefaultPath)
	blugeWriter, err := bluge.OpenWriter(blugeCfg)
	req.NoError(err)
	defer blugeWriter.Close()

	// 1. Create channel to wait for a signal at the end of process
	done := make(chan struct{})
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	telemetryChan := make(chan event.Event)
	fileAnalyzeChan := make(chan event.Event)
	fileRequestChan := make(chan<- domain.FileDownloaderRequest, 5000)
	supervisor := workers.NewSupervisor(log, telemetryChan, 200*time.Millisecond)
	registry := runtime.NewRegistry()
	messageRepository := storage.NewMessageRepository(db, log, lo.ToPtr(100))
	analysisRepository := storage.NewAnalysisRepository(db, blugeWriter, log, lo.ToPtr(50), 50)
	fileTaskRepository := storage.NewFileTaskRepository(db, log)
	manager := runtime.NewCoordinator(log)
	orchestrator := runtime.NewOrchestrator(
		log, supervisor, registry, telemetryChan, fileAnalyzeChan, fileRequestChan,
		messageRepository,
		analysisRepository,
		fileTaskRepository,
		manager,
		10, 1000,
		3*time.Second,
		500*time.Millisecond,
		500*time.Millisecond,
		100*time.Millisecond,
		100*time.Millisecond,
		100*time.Millisecond,
		'*',
		500,
		300,
		0.4, 0.6,
		100,
		30*time.Second,
		5,
	)
	ctrl := gomock.NewController(t)
	mockMessageRepository := mocks.NewMockIMessageRepository(ctrl)
	mockMessageRepository.EXPECT().
		StoreMessage(gomock.Any()).
		Do(func(msg any) {
			close(done) // Signaling a message has been received
		}).
		Return(nil).
		Times(1)

	w := orchestrator.PrepareFanouts()
	supervisor.Add(w...)

	id := 1
	room := chat.NewRoom(chat.RoomID(id))
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
	err = orchestrator.PostMessage(ctx, chat.PostMessageCommand{
		Room:      id,
		UserID:    userID,
		Content:   content,
		CreatedAt: at,
	})
	req.NoError(err)

	// And wait time for channels & goroutines
	select {
	case <-done:
		// Then the message has reached the repository
	case <-time.After(2 * time.Second):

		req.Fail("Timeout: message has never reached the repository")
	}
}
