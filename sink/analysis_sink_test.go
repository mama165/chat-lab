package sink_test

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"chat-lab/mocks"
	"chat-lab/sink"
	"context"
	"errors"
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
) (*sink.AnalysisSink, *mocks.MockIAnalysisRepository, chan domain.AnalysisJob) {
	mockRepoAnalysis := mocks.NewMockIAnalysisRepository(ctrl)
	jobChan := make(chan domain.AnalysisJob, 10)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	s := sink.NewAnalysisSink(mockRepoAnalysis, jobChan, logger, maxSize, timeout, timeout)
	return s, mockRepoAnalysis, jobChan
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

func TestJobQueuedOnlyForSupportedMimeTypes(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	// Use maxSize 1 to trigger immediate flush on each consumption.
	s, mockRepo, jobChan := setupSink(ctrl, 1, 1*time.Hour)

	// SCENARIO 1: PDF file -> stored, then queued for the specialists.
	pdfID := uuid.New()
	mockRepo.EXPECT().
		StoreBatch(gomock.Any()).
		DoAndReturn(func(analyses []storage.Analysis) error {
			req.Len(analyses, 1)
			// The record lives under the drive ID: it is the namespace used later
			// to fetch it back and merge the specialist results.
			req.Equal("drive-1", analyses[0].Namespace)
			req.Empty(jobChan, "a file must not be queued before its record is stored")
			return nil
		}).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:                pdfID,
		DriveID:           "drive-1",
		Path:              "/test/document.pdf",
		Size:              42,
		RawMimeType:       string(mimetypes.ApplicationPDF),
		EffectiveMimeType: mimetypes.ApplicationPDF,
		SourceType:        "file",
	})
	req.NoError(err)

	req.Len(jobChan, 1)
	job := <-jobChan
	req.Equal(domain.AnalysisJob{
		DriveID:           "drive-1",
		FileID:            domain.FileID(pdfID.String()),
		Path:              "/test/document.pdf",
		Size:              42,
		EffectiveMimeType: mimetypes.ApplicationPDF,
	}, job)

	// SCENARIO 2: Text file -> stored, but not queued.
	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	err = s.Consume(context.Background(), event.FileAnalyse{
		Id:          uuid.New(),
		DriveID:     "drive-1",
		Path:        "/test/notes.txt",
		RawMimeType: string(mimetypes.TextPlain),
		SourceType:  "file",
	})
	req.NoError(err)
	req.Empty(jobChan)
}

func TestNoJobQueuedWhenStorageFails(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	s, mockRepo, jobChan := setupSink(ctrl, 1, 1*time.Hour)
	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(errors.New("disk full")).Times(1)

	err := s.Consume(context.Background(), event.FileAnalyse{
		Id:          uuid.New(),
		DriveID:     "drive-1",
		Path:        "/test/document.pdf",
		RawMimeType: string(mimetypes.ApplicationPDF),
	})
	req.Error(err)
	req.Empty(jobChan)
}

// When the specialists are slower than the scan, the job queue fills up and the
// sink waits. Close must release it so that the program can stop.
func TestCloseReleasesSinkBlockedOnFullJobQueue(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	mockRepo := mocks.NewMockIAnalysisRepository(ctrl)
	jobChan := make(chan domain.AnalysisJob) // unbuffered and never read: always full
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	s := sink.NewAnalysisSink(mockRepo, jobChan, logger, 1, 1*time.Hour, 1*time.Hour)

	mockRepo.EXPECT().StoreBatch(gomock.Any()).Return(nil).Times(1)

	done := make(chan error, 1)
	go func() {
		done <- s.Consume(context.Background(), event.FileAnalyse{
			Id:          uuid.New(),
			DriveID:     "drive-1",
			Path:        "/test/document.pdf",
			RawMimeType: string(mimetypes.ApplicationPDF),
		})
	}()

	select {
	case <-done:
		t.Fatal("the sink should be waiting for room in the job queue")
	case <-time.After(50 * time.Millisecond):
	}

	s.Close()
	s.Close() // safe to call twice

	select {
	case err := <-done:
		req.NoError(err)
	case <-time.After(2 * time.Second):
		t.Fatal("Close did not release the blocked sink")
	}
}

func TestSpecialistResultsAreMergedIntoStoredRecord(t *testing.T) {
	req := require.New(t)
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	s, mockRepo, _ := setupSink(ctrl, 1, 1*time.Hour)

	fileID := uuid.New()
	pdf := domain.MetricPDF
	mockRepo.EXPECT().
		FetchFullByEntityId("drive-1", fileID).
		Return(storage.Analysis{
			ID:        fileID,
			EntityId:  fileID,
			Namespace: "drive-1",
			Summary:   "/test/document.pdf",
			Payload:   storage.FileDetails{Filename: "/test/document.pdf"},
		}, nil).Times(1)

	mockRepo.EXPECT().
		Store(gomock.Any()).
		DoAndReturn(func(a storage.Analysis) error {
			req.Equal("Annual report", a.Summary)
			req.Contains(a.Tags, "author:Jane")
			details, ok := a.Payload.(storage.FileDetails)
			req.True(ok)
			req.Equal("first page\nsecond page", details.Content)
			return nil
		}).Times(1)

	err := s.Consume(context.Background(), event.AnalysisSegment{
		DriveID: "drive-1",
		FileID:  domain.FileID(fileID.String()),
		Metrics: []event.AnalysisMetric{{
			ID: &pdf,
			Resp: &domain.Response{OneOf: domain.DocumentData{
				Title:  "Annual report",
				Author: "Jane",
				Pages: []domain.Page{
					{Number: 1, Content: "first page"},
					{Number: 2, Content: "second page"},
				},
			}},
		}},
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
