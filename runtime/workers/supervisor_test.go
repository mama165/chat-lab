package workers

import (
	"chat-lab/domain/event"
	"chat-lab/mocks"
	"context"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"
)

func TestSupervisor_RestartOnPanic(t *testing.T) {
	req := require.New(t)
	log := slog.Default()
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()
	workerMock := mocks.NewMockWorker(ctrl)

	// The counter is written by the supervised goroutine and read by the test.
	var calls atomic.Int32
	workerMock.EXPECT().
		Run(gomock.Any()).
		DoAndReturn(func(ctx context.Context) error {
			calls.Add(1)
			panic("boom")
		}).
		AnyTimes()

	telemetryChan := make(chan event.Event)
	sup := NewSupervisor(log, telemetryChan, 200*time.Millisecond)

	ctx, cancel := context.WithTimeout(context.Background(), 1*time.Second)
	defer cancel()

	go sup.Add(workerMock).Run(ctx)

	// Waiting for panics and restarts
	time.Sleep(900 * time.Millisecond)

	req.GreaterOrEqual(calls.Load(), int32(2))
}

func TestSupervisor_StopOnSuccess(t *testing.T) {
	req := require.New(t)
	log := slog.Default()
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	workerMock := mocks.NewMockWorker(ctrl)

	// Given a worker running only once
	workerMock.EXPECT().
		Run(gomock.Any()).
		Return(nil).
		Times(1)

	telemetryChan := make(chan event.Event)
	sup := NewSupervisor(log, telemetryChan, 200*time.Millisecond)

	// Given a channel to notify when Run() terminated
	done := make(chan struct{})

	go func() {
		sup.Add(workerMock).Run(context.Background())
		close(done)
	}()

	select {
	case <-done:
		// Then supervisor detected  a success, returned nil and stopped
	case <-time.After(500 * time.Millisecond):
		req.Fail("Supervisor should have stopped after worker success")
	}
}
