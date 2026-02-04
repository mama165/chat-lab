package services

import (
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"context"
	"log/slog"
	"sync/atomic"
	"time"

	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
)

type IAnalyzerService interface {
	Analyze(ctx context.Context, request analyzer.FileAnalyzerRequest) error
}

type AnalyzerService struct {
	log                *slog.Logger
	validator          *validator.Validate
	repository         storage.IAnalysisRepository
	eventChan          chan event.Event
	countAnalyzedFiles *analyzer.CountAnalyzedFiles
}

func NewAnalyzerService(
	log *slog.Logger,
	repository storage.IAnalysisRepository,
	eventChan chan event.Event,
	countAnalyzedFiles *analyzer.CountAnalyzedFiles) *AnalyzerService {
	return &AnalyzerService{
		log:                log,
		repository:         repository,
		validator:          validator.New(),
		eventChan:          eventChan,
		countAnalyzedFiles: countAnalyzedFiles,
	}
}

// Analyze validates the incoming file metadata and pushes it into an internal channel for asynchronous batching.
// It updates global atomic counters for real-time telemetry.
// Backpressure mechanism:
// This method will block if the fileScanChan is full, effectively slowing down
// the gRPC stream and the scanner (Producer) until the background Worker (Consumer)
// flushes data to the storage layer.
func (s *AnalyzerService) Analyze(ctx context.Context, request analyzer.FileAnalyzerRequest) error {
	if err := s.validator.Struct(request); err != nil {
		return err
	}
	select {
	case <-ctx.Done():
		s.log.Debug("Context done, skipping file analyzer")
		return ctx.Err()
	case s.eventChan <- toEvent(request):
		s.log.Debug("Received one file to analyze", "bytes", request.Size)
		s.increment(request.Size)
	}
	return nil
}

func toEvent(request analyzer.FileAnalyzerRequest) event.Event {
	return event.Event{
		Type:      event.FileAnalyzeType,
		CreatedAt: time.Now().UTC(),
		Payload: event.FileAnalyse{
			Id:         uuid.New(),
			Path:       request.Path,
			DriveID:    request.DriveID,
			Size:       request.Size,
			Attributes: request.Attributes,
			MimeType:   request.MimeType,
			MagicBytes: request.MagicBytes,
			ScannedAt:  request.ScannedAt,
			SourceType: string(request.SourceType),
		},
	}
}

// increment performs thread-safe updates to the shared analysis statistics.
func (s *AnalyzerService) increment(fileSize uint64) {
	atomic.AddUint64(&s.countAnalyzedFiles.FilesReceived, 1)
	atomic.AddUint64(&s.countAnalyzedFiles.BytesProcessed, fileSize)
}
