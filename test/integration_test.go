package test

import (
	"chat-lab/domain"
	"chat-lab/mocks"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/storage"
	"context"
	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/mama165/sdk-go/logs"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
	"log/slog"
	"testing"
	"time"
)

func Test_Scenario(t *testing.T) {
	ctx := context.Background()
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
	req.NoError(err)
	defer db.Close()

	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	supervisor := workers.NewSupervisor(log)
	orchestrator := runtime.NewOrchestrator(log, supervisor, 100)
	ctrl := gomock.NewController(t)
	mockMessageRepository := mocks.NewMockRepository(ctrl)
	mockMessageRepository.EXPECT().StoreMessage(gomock.Any()).Return(nil)

	mockTimelineSink := mocks.NewMockEventSink(ctrl)
	mockTimelineSink.EXPECT().Consume(gomock.Any()).Return()
	diskSink := storage.NewDiskSink(mockMessageRepository, log)
	orchestrator.RegisterSinks(mockTimelineSink, diskSink)
	id := 1
	room := domain.NewRoom(id)
	orchestrator.RegisterRoom(room)

	go orchestrator.Start(ctx)
	defer orchestrator.Stop()

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
	time.Sleep(100 * time.Millisecond)

	// Then supervisor can be canceled
	req.NotNil(supervisor.Cancel)

	// And message is stored in database
	//messages, err := mockMessageRepository.GetMessages(id)
	//req.NoError(err)
	//req.Len(messages, 1)
}
