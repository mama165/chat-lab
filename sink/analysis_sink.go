package sink

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
)

type AnalysisSink struct {
	mu                 sync.Mutex
	timer              *time.Timer
	analysisRepository storage.IAnalysisRepository
	fileTaskRepository storage.IFileTaskRepository
	log                *slog.Logger
	events             []event.FileAnalyse
	maxAnalyzedEvent   int
	bufferTimeout      time.Duration
	specialistTimeout  time.Duration
}

func NewAnalysisSink(
	analysisRepository storage.IAnalysisRepository,
	fileTaskRepository storage.IFileTaskRepository,
	log *slog.Logger,
	maxAnalyzedEvent int,
	bufferTimeout time.Duration,
	specialistTimeout time.Duration,
) *AnalysisSink {
	return &AnalysisSink{
		analysisRepository: analysisRepository,
		fileTaskRepository: fileTaskRepository,
		log:                log,
		maxAnalyzedEvent:   maxAnalyzedEvent,
		bufferTimeout:      bufferTimeout,
		specialistTimeout:  specialistTimeout,
	}
}

// Consume implements the EventSink interface.
func (a *AnalysisSink) Consume(_ context.Context, e contract.FileAnalyzerEvent) error {
	// Détection du type d'événement via le Payload
	switch evt := e.(type) {
	case event.FileAnalyse:
		return a.handleNewFile(evt)

	case event.AnalysisSegment:
		return a.handleAnalysisSegment(evt)

	default:
		return nil
	}
}

func (a *AnalysisSink) handleNewFile(e event.FileAnalyse) error {
	a.mu.Lock()
	// 2. State update: Append the event to the current slice
	a.events = append(a.events, e)

	// 3. Timer management: if this is the first event of a new batch,
	// start a background timer to ensure data is not stuck if the throughput is low.
	// We only start it if no other timer is currently running (timer == nil).
	if len(a.events) == 1 && a.timer == nil {
		a.timer = time.AfterFunc(a.bufferTimeout, func() {
			a.log.Debug("Flushing events", "events size", len(a.events))
			if err := a.flush(); err != nil {
				a.log.Error("Batching: Timeout flush failed", "error", err)
			}
		})
	}

	// 4. Threshold check: determine if the buffer reached its capacity
	isFull := len(a.events) >= a.maxAnalyzedEvent
	a.mu.Unlock()

	// 5. Size-based flush: triggered if the batch is full
	if isFull {
		return a.flush()
	}

	return nil
}

// handleAnalysisSegment performs the asynchronous enrichment of a file's analysis.
// It retrieves the existing metadata from storage (created during the initial scan)
// and merges the new specialist results (scores, transcriptions, or metadata)
// into the record. This ensures that the final Analysis object is an aggregate
// of both the raw file system data and the deep insights provided by specialists.
func (a *AnalysisSink) handleAnalysisSegment(segment event.AnalysisSegment) error {
	fileID, err := uuid.Parse(string(segment.FileID))
	if err != nil {
		return fmt.Errorf("invalid file ID format: %w", err)
	}
	analysis, err := a.analysisRepository.FetchFullByEntityId(segment.DriveID, fileID)
	if err != nil {
		return fmt.Errorf("could not fetch analysis for enrichment: %w", err)
	}

	if analysis.Scores == nil {
		analysis.Scores = make(map[domain.Metric]float64)
	}

	for _, result := range segment.Metrics {
		if result.SpecialistErr != nil {
			a.log.Warn("specialist error reported", "metric", result.ID, "err", result.SpecialistErr)
			continue
		}

		if result.Resp == nil || result.Resp.OneOf == nil {
			continue
		}

		switch data := result.Resp.OneOf.(type) {
		case domain.Score:
			if result.ID != nil {
				analysis.Scores[*result.ID] = data.Score
				if data.Label != "" {
					analysis.Tags = append(analysis.Tags, data.Label)
				}
			}
		case domain.AudioData:
			if details, ok := analysis.Payload.(storage.FileDetails); ok {
				details.Content = data.Transcription
				analysis.Payload = details
			}

		case domain.DocumentData:
			if data.Title != "" {
				analysis.Summary = data.Title
			}
			if data.Author != "" {
				analysis.Tags = append(analysis.Tags, "author:"+data.Author)
			}
		}
	}

	analysis.Version = uuid.New()
	if err := a.analysisRepository.Store(analysis); err != nil {
		return fmt.Errorf("failed to save enriched analysis: %w", err)
	}

	a.log.Info("Analysis enriched",
		"driveID", segment.DriveID,
		"fileID", fileID,
		"metrics_count", len(segment.Metrics))

	return nil
}

// flush handles the transition of data from the transient buffer to the specialists and persistent storage.
// It employs an 'atomic swap' pattern to minimize lock contention.
func (a *AnalysisSink) flush() error {
	a.mu.Lock()

	// Stop and clear the timer to prevent redundant flushes.
	if a.timer != nil {
		a.timer.Stop()
		a.timer = nil
	}

	// Double-check for empty buffer in case of concurrent flush calls.
	if len(a.events) == 0 {
		a.mu.Unlock()
		return nil
	}

	// Perform a 'memory swap' to release the lock as soon as possible.
	// This allows start filling the next batch immediately.
	batchEvents := a.events
	a.events = make([]event.FileAnalyse, 0, a.maxAnalyzedEvent)

	a.mu.Unlock()

	return a.processAndStore(batchEvents)
}

// processAndStore executes the core business logic: it persists basic file metadata
// and offloads heavy analysis (Audio, PDF) to a persistent task queue.
// This ensures high throughput for the scanner by decoupling immediate storage
// from long-running specialized processing.
func (a *AnalysisSink) processAndStore(events []event.FileAnalyse) error {
	allAnalyses := make([]storage.Analysis, 0, len(events))

	for _, evt := range events {
		analysis := a.buildFinalAnalysis(evt, domain.SpecialistResponse{})
		allAnalyses = append(allAnalyses, analysis)

		if _, ok := mimetypes.IsAuthorized(evt.RawMimeType); ok {
			task := storage.FileTask{
				ID:                evt.Id.String(),
				DriveID:           evt.DriveID,
				Path:              evt.Path,
				RawMimeType:       evt.RawMimeType,
				EffectiveMimeType: evt.EffectiveMimeType,
				Size:              evt.Size,
				Priority:          storage.NORMAL,
				CreatedAt:         time.Now(),
			}

			if err := a.fileTaskRepository.EnqueueTask(task); err != nil {
				a.log.Error("Failed to enqueue task", "path", evt.Path, "error", err)
			}
		}
	}

	if len(allAnalyses) > 0 {
		return a.analysisRepository.StoreBatch(allAnalyses)
	}
	return nil
}

// buildFinalAnalysis maps and merges raw file events with specialized analysis results.
// It acts as a decorator that enriches the initial metadata (path, mimetype)
// with deeper insights like extracted titles, authors, or sentiment/toxicity scores.
func (a *AnalysisSink) buildFinalAnalysis(evt event.FileAnalyse, resp domain.SpecialistResponse) storage.Analysis {
	analysis := storage.Analysis{
		ID:        evt.Id,
		EntityId:  evt.Id,
		Namespace: "file-room",
		At:        evt.ScannedAt,
		Summary:   evt.Path,
		Tags:      []string{evt.RawMimeType, evt.SourceType},
		Scores:    make(map[domain.Metric]float64),
		Version:   uuid.New(),
	}

	// Prepare payload (empty for now)
	payload := storage.FileDetails{
		Filename: evt.Path,
		MimeType: evt.RawMimeType,
		Size:     evt.Size,
	}

	for _, res := range resp.Res {
		switch data := res.Resp.OneOf.(type) {

		case domain.AudioData:
			// Assign the transcription to the file content
			payload.Content = data.Transcription
			analysis.Summary = "Audio Transcription"
		case domain.DocumentData:
			if data.Title != "" {
				analysis.Summary = data.Title
			}
			if data.Author != "" {
				analysis.Tags = append(analysis.Tags, "author:"+data.Author)
			}

			// Extract content from the first page (or merge all pages)
			if len(data.Pages) > 0 {
				payload.Content = data.Pages[0].Content
				// Optional debug log
				// a.log.Debug("Content extracted", "length", len(payload.Content))
			}

		case domain.Score:
			if res.ID != nil {
				analysis.Scores[*res.ID] = data.Score
				analysis.Tags = append(analysis.Tags, data.Label)

				// (Optionnal)
				// case specialist.AudioData:
				//    payload.Content = data.Transcription
			}
		}
	}

	analysis.Payload = payload

	return analysis
}
