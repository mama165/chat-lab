package sink_test

import (
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/domain/specialist"
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

func setupSink(ctrl *gomock.Controller, maxSize int, timeout time.Duration) (*sink.AnalysisSink, *mocks.MockIAnalysisRepository, *mocks.MockSpecialistCoordinator) {
	mockRepo := mocks.NewMockIAnalysisRepository(ctrl)
	mockCoordinator := mocks.NewMockSpecialistCoordinator(ctrl)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	s := sink.NewAnalysisSink(mockCoordinator, mockRepo, logger, maxSize, timeout)
	return s, mockRepo, mockCoordinator
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

	timeout := 50 * time.Millisecond
	s, mockRepo, _ := setupSink(ctrl, 100, timeout)

	mockRepo.EXPECT().StoreBatch(gomock.Any()).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:      uuid.New(),
		DriveID: uuid.New().String(),
	})
	req.NoError(err)

	time.Sleep(timeout + 50*time.Millisecond)
}

func TestBroadcastOnlyForPDFOrFileSource(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	// Use maxSize 1 to trigger flush immediately on each Consume
	s, mockRepo, mockCoordinator := setupSink(ctrl, 1, 1*time.Hour)

	// SCENARIO 1: PDF File -> Should Broadcast
	mockRequest := specialist.AnalysisRequest{
		MimeType: mimetypes.ApplicationPDF,
	}
	mockCoordinator.EXPECT().Broadcast(gomock.Any(), mockRequest).Return(specialist.AnalysisResponse{}, nil).Times(1)
	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:         uuid.New(),
		DriveID:    uuid.New().String(),
		MimeType:   string(mimetypes.ApplicationPDF),
		SourceType: "file",
	})
	req.NoError(err)

	// SCENARIO 2: Generic TXT without "file" source -> Should NOT Broadcast
	// (Note: no call to mockCoordinator.EXPECT() means it should fail if called)
	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	err = s.Consume(context.Background(), event.FileAnalyse{
		Id:         uuid.New(),
		DriveID:    uuid.New().String(),
		MimeType:   string(mimetypes.TextPlain),
		SourceType: "random_source",
	})
	req.NoError(err)
}

func TestConcurrentAccessSafety(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	numWorkers := 10
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

func TestErrorInvalidDriveID(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	s, _, _ := setupSink(ctrl, 1, 1*time.Hour)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:      uuid.New(),
		DriveID: "not-a-uuid",
	})

	req.Error(err)
	req.Contains(err.Error(), "invalid DriveID")
}
