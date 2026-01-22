package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/mocks"
	"context"
	"log/slog"
	"testing"
	"time"

	"github.com/mama165/sdk-go/logs"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func TestEventFanoutWorker_Fanout(t *testing.T) {
	req := require.New(t)
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()
	mockRegistry := mocks.NewMockIRegistry(ctrl)

	mockSink := mocks.NewMockEventSink[contract.DomainEvent](ctrl)
	mockSink1 := mocks.NewMockEventSink[contract.FileAnalyzerEvent](ctrl)
	roomSinks := []contract.EventSink[contract.DomainEvent]{mockSink, mockSink}

	fanoutWorker := NewEventFanoutWorker(
		log, mockSink, mockSink1,
		mockRegistry, nil, nil, 10*time.Second)

	done := make(chan struct{})
	count := 0
	// Given two sink exist
	mockRegistry.EXPECT().GetSinksForRoom(gomock.Any()).Return(roomSinks).Times(1)
	// Given permanentSink and roomSink are consumed
	mockSink.EXPECT().Consume(gomock.Any(), gomock.Any()).Do(
		func(ctx context.Context, evt contract.DomainEvent) {
			count++
			if count == 4 {
				close(done)
			}
		}).Return(nil).
		Times(4)

	evt := event.SanitizedMessage{}

	// When an event is received and handled by worker
	fanoutWorker.Fanout(evt)

	//Then success happens
	select {
	case <-done:
	case <-time.After(1 * time.Second):
		req.Fail("Goroutine did not terminated at time")
	}
}

func TestEventFanoutWorker_SinkTimeout(t *testing.T) {
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	mockRegistry := mocks.NewMockIRegistry(ctrl)
	mockSink := mocks.NewMockEventSink[contract.DomainEvent](ctrl)
	mockSink1 := mocks.NewMockEventSink[contract.FileAnalyzerEvent](ctrl)
	roomSinks := []contract.EventSink[contract.DomainEvent]{mockSink}

	sinkTimeout := 20 * time.Millisecond
	fanoutWorker := NewEventFanoutWorker(
		log, mockSink, mockSink1,
		mockRegistry, nil, nil, sinkTimeout)

	// Given two sink exist
	mockRegistry.EXPECT().GetSinksForRoom(gomock.Any()).Return(roomSinks).Times(1)
	// Given permanentSink and roomSink are consumed
	mockSink.EXPECT().Consume(gomock.Any(), gomock.Any()).
		DoAndReturn(
			func(ctx context.Context, evt contract.DomainEvent) error {
				<-ctx.Done()     // Waiting for timeout to trigger cancellation
				return ctx.Err() // Sending back "context deadline exceeded"
			},
		).Return(nil).
		Times(1)

	evt := event.SanitizedMessage{}

	// When an event is received and handled by worker
	fanoutWorker.Fanout(evt)

	//Then no sink were consumed
	// And waiting more than timeout to let goroutine finish
	time.Sleep(50 * time.Millisecond)
}
