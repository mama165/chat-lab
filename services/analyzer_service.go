package services

import (
	"chat-lab/domain/analyzer"
	"chat-lab/infrastructure/storage"
	"context"
	"log/slog"
	"sync/atomic"

	"github.com/go-playground/validator/v10"
)

type IAnalyzerService interface {
	Analyze(ctx context.Context, request analyzer.FileAnalyzerRequest) error
}

type AnalyzerService struct {
	log                *slog.Logger
	validator          *validator.Validate
	repository         storage.IAnalysisRepository
	fileScanChan       chan analyzer.FileAnalyzerRequest
	countAnalyzedFiles *analyzer.CountAnalyzedFiles
}

func NewAnalyzerService(
	log *slog.Logger,
	repository storage.IAnalysisRepository,
	bufferSize int,
	countAnalyzedFiles *analyzer.CountAnalyzedFiles) *AnalyzerService {
	return &AnalyzerService{
		log:                log,
		repository:         repository,
		validator:          validator.New(),
		fileScanChan:       make(chan analyzer.FileAnalyzerRequest, bufferSize),
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
		return ctx.Err()
	case s.fileScanChan <- request:
		s.increment(request.Size)
	}
	return nil
}

// increment performs thread-safe updates to the shared analysis statistics.
func (s *AnalyzerService) increment(fileSize uint64) {
	atomic.AddUint64(&s.countAnalyzedFiles.FilesReceived, 1)
	atomic.AddUint64(&s.countAnalyzedFiles.BytesProcessed, fileSize)
}
