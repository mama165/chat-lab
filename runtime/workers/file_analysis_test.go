package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/mocks"
	"chat-lab/observability"
	"context"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func newAnalysisWorkerForTest(t *testing.T) (
	*FileAnalysisWorker,
	*mocks.MockSpecialistCoordinator,
	*mocks.MockEventSink[contract.FileAnalyzerEvent],
	*observability.MonitoringManager,
	chan domain.AnalysisJob,
) {
	t.Helper()
	ctrl := gomock.NewController(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	coordinator := mocks.NewMockSpecialistCoordinator(ctrl)
	analysisSink := mocks.NewMockEventSink[contract.FileAnalyzerEvent](ctrl)
	monitoring := observability.NewMonitoringManager(logger)
	jobChan := make(chan domain.AnalysisJob, 10)
	telemetryChan := make(chan event.Event, 10)

	worker := NewFileAnalysisWorker(logger, coordinator, analysisSink, monitoring, jobChan, telemetryChan)
	return worker, coordinator, analysisSink, monitoring, jobChan
}

func runUntilStopped(t *testing.T, worker *FileAnalysisWorker) (stop func()) {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = worker.Run(ctx)
	}()
	return func() {
		cancel()
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			t.Fatal("worker did not stop after context cancellation")
		}
	}
}

func TestFileAnalysisWorker_StoresSpecialistResults(t *testing.T) {
	req := require.New(t)
	worker, coordinator, analysisSink, monitoring, jobChan := newAnalysisWorkerForTest(t)

	pdf := domain.MetricPDF
	response := domain.Response{OneOf: domain.DocumentData{Title: "Annual report"}}
	job := domain.AnalysisJob{
		DriveID:           "drive-1",
		FileID:            "file-1",
		Path:              "/docs/report.pdf",
		Size:              2048,
		EffectiveMimeType: mimetypes.ApplicationPDF,
	}

	coordinator.EXPECT().
		Analyze(gomock.Any(), domain.SpecialistRequest{
			FileID:            job.FileID,
			Path:              job.Path,
			EffectiveMimeType: job.EffectiveMimeType,
		}).
		Return([]domain.SpecialistResult{{ID: &pdf, Resp: &response}}, nil).
		Times(1)

	stored := make(chan event.AnalysisSegment, 1)
	analysisSink.EXPECT().
		Consume(gomock.Any(), gomock.Any()).
		DoAndReturn(func(_ context.Context, e contract.FileAnalyzerEvent) error {
			stored <- e.(event.AnalysisSegment)
			return nil
		}).
		Times(1)

	stop := runUntilStopped(t, worker)
	jobChan <- job

	select {
	case segment := <-stored:
		req.Equal("drive-1", segment.DriveID)
		req.Equal(domain.FileID("file-1"), segment.FileID)
		req.Len(segment.Metrics, 1)
		req.Equal(&pdf, segment.Metrics[0].ID)
		req.Equal(&response, segment.Metrics[0].Resp)
	case <-time.After(2 * time.Second):
		t.Fatal("specialist results were never handed to the sink")
	}
	stop()

	req.Equal(uint64(2048), atomic.LoadUint64(&monitoring.MasterBytes))
}

// A file that cannot be analyzed must not stop the worker: the next file still goes through.
func TestFileAnalysisWorker_KeepsGoingAfterFailure(t *testing.T) {
	req := require.New(t)
	worker, coordinator, analysisSink, monitoring, jobChan := newAnalysisWorkerForTest(t)

	pdf := domain.MetricPDF
	gomock.InOrder(
		coordinator.EXPECT().Analyze(gomock.Any(), gomock.Any()).
			Return(nil, errors.New("file is too large")),
		coordinator.EXPECT().Analyze(gomock.Any(), gomock.Any()).
			Return([]domain.SpecialistResult{{ID: &pdf, Resp: &domain.Response{}}}, nil),
	)

	stored := make(chan struct{}, 1)
	analysisSink.EXPECT().
		Consume(gomock.Any(), gomock.Any()).
		DoAndReturn(func(_ context.Context, _ contract.FileAnalyzerEvent) error {
			stored <- struct{}{}
			return nil
		}).
		Times(1)

	stop := runUntilStopped(t, worker)
	jobChan <- domain.AnalysisJob{FileID: "too-large", Path: "/docs/huge.pdf"}
	jobChan <- domain.AnalysisJob{FileID: "fine", Path: "/docs/ok.pdf"}

	select {
	case <-stored:
	case <-time.After(2 * time.Second):
		t.Fatal("the second file was never analyzed")
	}
	stop()

	req.Equal(uint64(1), atomic.LoadUint64(&monitoring.ErrorCount))
}

// When no specialist handles the file type, there is nothing to store.
func TestFileAnalysisWorker_NoSpecialistNoStorage(t *testing.T) {
	worker, coordinator, _, _, jobChan := newAnalysisWorkerForTest(t)

	analyzed := make(chan struct{})
	coordinator.EXPECT().Analyze(gomock.Any(), gomock.Any()).
		DoAndReturn(func(_ context.Context, _ domain.SpecialistRequest) ([]domain.SpecialistResult, error) {
			close(analyzed)
			return nil, nil
		}).Times(1)
	// No expectation on the sink: gomock fails the test if Consume is called.

	stop := runUntilStopped(t, worker)
	jobChan <- domain.AnalysisJob{FileID: "file-1", Path: "/docs/archive.zip"}

	select {
	case <-analyzed:
	case <-time.After(2 * time.Second):
		t.Fatal("the job was never picked up")
	}
	stop()
}
