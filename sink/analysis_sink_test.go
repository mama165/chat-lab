package sink_test

import (
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
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

// setupSink prepares the test environment with necessary mocks and logger.
func setupSink(ctrl *gomock.Controller,
	maxSize int,
	timeout time.Duration,
) (*sink.AnalysisSink, *mocks.MockIAnalysisRepository, *mocks.MockIFileTaskRepository) {
	mockRepoAnalysis := mocks.NewMockIAnalysisRepository(ctrl)
	mockRepoFileTask := mocks.NewMockIFileTaskRepository(ctrl)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	// Updated constructor call to match the new asynchronous architecture.
	s := sink.NewAnalysisSink(mockRepoAnalysis, mockRepoFileTask, logger, maxSize, timeout, timeout)
	return s, mockRepoAnalysis, mockRepoFileTask
}

func TestFlushTriggeredBySizeLimit(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	maxSize := 3
	s, mockRepo, _ := setupSink(ctrl, maxSize, 1*time.Hour)

	mockRepo.EXPECT().
		StoreBatch(gomock.Any()).
		DoAndReturn(func(analyses []storage.Analysis) error {
			req.Equal(maxSize, len(analyses))
			return nil
		}).Times(1)

	for i := 0; i < maxSize; i++ {
		err := s.Consume(context.Background(), event.FileAnalyse{
			Id:      uuid.New(),
			DriveID: uuid.New().String(),
			Path:    "/tmp/test.txt",
		})
		req.NoError(err)
	}
}

func TestFlushTriggeredByTimeout(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	timeout := 10 * time.Millisecond // Shorter timeout for faster test execution.
	s, mockRepo, _ := setupSink(ctrl, 100, timeout)

	mockRepo.EXPECT().StoreBatch(gomock.Any()).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:      uuid.New(),
		DriveID: uuid.New().String(),
	})
	req.NoError(err)

	time.Sleep(timeout + 50*time.Millisecond)
}

func TestTaskEnqueuingForSpecificMimeTypes(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	// Use maxSize 1 to trigger immediate flush on each consumption.
	s, mockRepo, mockFileTaskRepo := setupSink(ctrl, 1, 1*time.Hour)

	// SCENARIO 1: PDF File -> Should trigger task enqueuing.
	mockFileTaskRepo.EXPECT().
		EnqueueTask(gomock.Any()).
		Return(nil).
		Times(1)

	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:         uuid.New(),
		Path:       "/test/document.pdf",
		MimeType:   string(mimetypes.ApplicationPDF),
		SourceType: "file",
	})
	req.NoError(err)

	// SCENARIO 2: Text file -> Should NOT trigger task enqueuing.
	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	err = s.Consume(context.Background(), event.FileAnalyse{
		Id:         uuid.New(),
		Path:       "/test/notes.txt",
		MimeType:   string(mimetypes.TextPlain),
		SourceType: "file",
	})
	req.NoError(err)
}

func TestConcurrentAccessSafety(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	numWorkers := 5
	eventsPerWorker := 10
	totalEvents := numWorkers * eventsPerWorker
	s, mockRepo, _ := setupSink(ctrl, totalEvents, 1*time.Hour)

	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	done := make(chan bool, numWorkers)
	for w := 0; w < numWorkers; w++ {
		go func() {
			for i := 0; i < eventsPerWorker; i++ {
				err := s.Consume(context.Background(), event.FileAnalyse{
					Id:      uuid.New(),
					DriveID: uuid.New().String(),
				})
				req.NoError(err)
			}
			done <- true
		}()
	}

	for w := 0; w < numWorkers; w++ {
		<-done
	}
}
