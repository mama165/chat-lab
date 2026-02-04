package sink

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"context"
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
// It acts as a high-performance buffer that aggregates file analysis events.
// The flush is triggered either by reaching a size threshold (maxAnalyzedEvent)
// or a time-based deadline (deliveryTimeout).
func (a *AnalysisSink) Consume(_ context.Context, e contract.FileAnalyzerEvent) error {
	evt, ok := e.(event.FileAnalyse)
	if !ok {
		return nil
	}

	a.mu.Lock()
	// 2. State update: Append the event to the current slice
	a.events = append(a.events, evt)

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

		if mimetypes.IsAudio(evt.MimeType) || mimetypes.IsPDF(evt.MimeType) {
			task := storage.FileTask{
				ID:        evt.Id.String(),
				Path:      evt.Path,
				MimeType:  evt.MimeType,
				Size:      evt.Size,
				Priority:  storage.NORMAL,
				CreatedAt: time.Now(),
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
		Tags:      []string{evt.MimeType, evt.SourceType},
		Scores:    make(map[domain.Metric]float64),
		Version:   uuid.New(),
	}

	// Prepare payload (empty for now)
	payload := storage.FileDetails{
		Filename: evt.Path,
		MimeType: evt.MimeType,
		Size:     evt.Size,
	}

	for metric, res := range resp.Results {
		switch data := res.OneOf.(type) {

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
			analysis.Scores[metric] = data.Score
			analysis.Tags = append(analysis.Tags, data.Label)

			// (Optionnal)
			// case specialist.AudioData:
			//    payload.Content = data.Transcription
		}
	}

	analysis.Payload = payload

	return analysis
}
