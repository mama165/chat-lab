package sink_test

import (
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/mocks"
	"chat-lab/sink"
	"context"
	"io"
	"log/slog"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func TestAnalysisSink_Consume(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	mockRepo := mocks.NewMockIAnalysisRepository(ctrl)
	// Silencing logs for clean test output
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	ctx := context.Background()
	driveID := uuid.New().String()

	t.Run("Flush triggered by size limit", func(t *testing.T) {
		maxSize := 3
		s := sink.NewAnalysisSink(mockRepo, logger, maxSize, 10*time.Second)

		// Expect exactly one batch call with 3 items
		mockRepo.EXPECT().
			StoreBatch(gomock.Any()).
			DoAndReturn(func(analyses []storage.Analysis) error {
				req.Equal(maxSize, len(analyses))
				return nil
			}).Times(1)

		for i := 0; i < maxSize; i++ {
			err := s.Consume(ctx, event.FileAnalyse{
				Id:      uuid.New(),
				DriveID: driveID,
				Path:    "/tmp/test.txt",
			})
			req.NoError(err)
		}
	})

	t.Run("Flush triggered by timeout (asynchronous)", func(t *testing.T) {
		timeout := 50 * time.Millisecond
		s := sink.NewAnalysisSink(mockRepo, logger, 100, timeout)

		// We send only 1 event, so size-based flush won't trigger.
		// The StoreBatch must be called by the timer.
		mockRepo.EXPECT().
			StoreBatch(gomock.Any()).
			DoAndReturn(func(analyses []storage.Analysis) error {
				req.Equal(1, len(analyses))
				return nil
			}).Times(1)

		err := s.Consume(ctx, event.FileAnalyse{
			Id:      uuid.New(),
			DriveID: driveID,
		})
		req.NoError(err)

		// Wait slightly more than the timeout to allow the goroutine to run
		time.Sleep(timeout + 30*time.Millisecond)
	})

	t.Run("Concurrent access safety", func(t *testing.T) {
		numWorkers := 10
		eventsPerWorker := 10
		totalEvents := numWorkers * eventsPerWorker

		// Set maxSize to totalEvents to trigger a single flush at the end
		s := sink.NewAnalysisSink(mockRepo, logger, totalEvents, 2*time.Second)

		mockRepo.EXPECT().
			StoreBatch(gomock.Any()).
			Return(nil).
			Times(1)

		// Using a wait group or a channel to wait for all goroutines
		done := make(chan bool, numWorkers)
		for w := 0; w < numWorkers; w++ {
			go func() {
				for i := 0; i < eventsPerWorker; i++ {
					_ = s.Consume(ctx, event.FileAnalyse{
						Id:      uuid.New(),
						DriveID: driveID,
					})
				}
				done <- true
			}()
		}

		for w := 0; w < numWorkers; w++ {
			<-done
		}
	})

	t.Run("Error handling: invalid DriveID", func(t *testing.T) {
		// If maxSize is 1, it flushes immediately
		s := sink.NewAnalysisSink(mockRepo, logger, 1, 1*time.Second)

		err := s.Consume(ctx, event.FileAnalyse{
			Id:      uuid.New(),
			DriveID: "invalid-uuid-format",
		})

		// Should fail during uuid.Parse in toAnalysis
		req.Error(err)
		req.Contains(err.Error(), "invalid DriveID")
	})
}
