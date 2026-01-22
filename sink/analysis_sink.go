package sink

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/domain/specialist"
	"chat-lab/infrastructure/storage"
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"

	"github.com/google/uuid"
)

type AnalysisSink struct {
	mu               sync.Mutex
	repository       storage.IAnalysisRepository
	log              *slog.Logger
	events           []event.FileAnalyse
	maxAnalyzedEvent int
	deliveryTimeout  time.Duration
}

func NewAnalysisSink(
	repository storage.IAnalysisRepository,
	log *slog.Logger,
	maxAnalyzedEvent int,
	deliveryTimeout time.Duration,
) *AnalysisSink {
	return &AnalysisSink{
		repository:       repository,
		log:              log,
		maxAnalyzedEvent: maxAnalyzedEvent,
		deliveryTimeout:  deliveryTimeout,
	}
}

// Consume implements the EventSink interface.
// It aggregates file analysis events into a buffer and flushes them to storage
// either when the buffer is full or after a certain duration has passed.
func (a *AnalysisSink) Consume(_ context.Context, e contract.FileAnalyzerEvent) error {
	evt, ok := e.(event.FileAnalyse)
	if !ok {
		return nil
	}

	a.mu.Lock()
	defer a.mu.Unlock()

	// If this is the start of a new batch (buffer is empty),
	// trigger a background timer to ensure a "time-based" flush.
	// This prevents data from being stuck in RAM if no more events arrive.
	if len(a.events) == 0 {
		time.AfterFunc(a.deliveryTimeout, func() {
			a.mu.Lock()
			defer a.mu.Unlock()

			// Only flush if the buffer still contains events that haven't
			// been cleared by a "size-based" flush in the meantime.
			if len(a.events) > 0 {
				a.log.Debug("Batching: Flush triggered by timeout")
				_ = a.flush()
			}
		})
	}

	// Accumulate the event in the current batch
	a.events = append(a.events, evt)

	// If the buffer reaches the maximum allowed size, trigger a "size-based" flush immediately.
	if len(a.events) >= a.maxAnalyzedEvent {
		a.log.Debug("Batching: Flush triggered by size limit")
		return a.flush()
	}

	return nil
}

// flush transforms the accumulated events and persists them via the repository.
// It resets the internal slice while preserving the allocated capacity.
func (a *AnalysisSink) flush() error {
	allAnalysis, err := toAnalysis(a.events)
	if err != nil {
		return err
	}

	if err = a.repository.StoreBatch(allAnalysis); err != nil {
		return err
	}

	// Clear the slice but keep the underlying array to reuse memory in the next batch.
	a.events = a.events[:0]
	return nil
}

// flush is a private helper to handle the repository call and slice reset
func toAnalysis(events []event.FileAnalyse) ([]storage.Analysis, error) {
	analyses := make([]storage.Analysis, 0, len(events))

	for _, evt := range events {
		driveId, err := uuid.Parse(evt.DriveID)
		if err != nil {
			return nil, fmt.Errorf("invalid DriveID %q: %w", evt.DriveID, err)
		}

		analysis := storage.Analysis{
			ID:        evt.Id,
			EntityId:  driveId,
			Namespace: "file-room",
			At:        evt.ScannedAt,
			Summary:   evt.Path,
			Tags: []string{
				evt.MimeType,
				evt.SourceType,
			},
			Scores:  make(map[specialist.Metric]float64),
			Payload: evt, // event brut pour traçabilité
			Version: uuid.New(),
		}

		analyses = append(analyses, analysis)
	}

	return analyses, nil
}
