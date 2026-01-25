package sink

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
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
	timer            *time.Timer
	coordinator      contract.SpecialistCoordinator
	repository       storage.IAnalysisRepository
	log              *slog.Logger
	events           []event.FileAnalyse
	maxAnalyzedEvent int
	deliveryTimeout  time.Duration
}

func NewAnalysisSink(
	coordinator contract.SpecialistCoordinator,
	repository storage.IAnalysisRepository,
	log *slog.Logger,
	maxAnalyzedEvent int,
	deliveryTimeout time.Duration,
) *AnalysisSink {
	return &AnalysisSink{
		coordinator:      coordinator,
		repository:       repository,
		log:              log,
		maxAnalyzedEvent: maxAnalyzedEvent,
		deliveryTimeout:  deliveryTimeout,
	}
}

// Consume implements the EventSink interface.
// It acts as a high-performance buffer that aggregates file analysis events.
// The flush is triggered either by reaching a size threshold (maxAnalyzedEvent)
// or a time-based deadline (deliveryTimeout).
func (a *AnalysisSink) Consume(ctx context.Context, e contract.FileAnalyzerEvent) error {
	evt, ok := e.(event.FileAnalyse)
	if !ok {
		return nil
	}

	// 1. Immediate validation: we fail fast if the data is corrupted
	// to avoid polluting the buffer with invalid records.
	if _, err := uuid.Parse(evt.DriveID); err != nil {
		return fmt.Errorf("invalid DriveID %q: %w", evt.DriveID, err)
	}

	a.mu.Lock()
	// 2. State update: Append the event to the current slice
	a.events = append(a.events, evt)

	// 3. Timer management: if this is the first event of a new batch,
	// start a background timer to ensure data is not stuck if the throughput is low.
	// We only start it if no other timer is currently running (timer == nil).
	if len(a.events) == 1 && a.timer == nil {
		a.timer = time.AfterFunc(a.deliveryTimeout, func() {
			if err := a.flush(ctx); err != nil {
				a.log.Error("Batching: Timeout flush failed", "error", err)
			}
		})
	}

	// 4. Threshold check: determine if the buffer reached its capacity
	isFull := len(a.events) >= a.maxAnalyzedEvent
	a.mu.Unlock()

	// 5. Size-based flush: triggered if the batch is full
	if isFull {
		return a.flush(ctx)
	}

	return nil
}

// flush handles the transition of data from the transient buffer to the specialists and persistent storage.
// It employs an 'atomic swap' pattern to minimize lock contention.
func (a *AnalysisSink) flush(ctx context.Context) error {
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

	// Create a dedicated context for this batch to prevent long-running sidecars
	// from blocking the entire pipeline.
	batchCtx, cancel := context.WithTimeout(ctx, a.deliveryTimeout)
	defer cancel()

	return a.processAndStore(batchCtx, batchEvents)
}

// processAndStore executes the core business logic: it enriches the events
// via gRPC specialists (Fan-Out) and persists the final results to BadgerDB.
func (a *AnalysisSink) processAndStore(ctx context.Context, events []event.FileAnalyse) error {
	type asyncResult struct {
		analysis storage.Analysis
		err      error
	}

	resChan := make(chan asyncResult, len(events))
	var wg sync.WaitGroup

	// Fan-Out: Launch a goroutine for each event to perform specialized analysis.
	for _, evt := range events {
		wg.Add(1)
		go func(e event.FileAnalyse) {
			defer wg.Done()

			var resp specialist.AnalysisResponse
			var err error

			// Only trigger specialized analysis if the file type requires it.
			if mimetypes.Matches(e.MimeType, mimetypes.ApplicationPDF) || e.SourceType == "file" {
				resp, err = a.coordinator.Broadcast(ctx, specialist.AnalysisRequest{
					Path:     e.Path,
					MimeType: e.MimeType,
				})
			}

			// Merge initial event data with specialist results (or fallback if error).
			resChan <- asyncResult{
				analysis: a.buildFinalAnalysis(e, resp),
				err:      err,
			}
		}(evt)
	}

	// Synchronization: Close the results channel once all goroutines are done.
	go func() {
		wg.Wait()
		close(resChan)
	}()

	// Fan-In: Collect all results from the channel.
	allAnalyses := make([]storage.Analysis, 0, len(events))
	for res := range resChan {
		if res.err != nil {
			a.log.Warn("Enrichment partial failure, storing basic metadata", "error", res.err)
		}
		allAnalyses = append(allAnalyses, res.analysis)
	}

	// Persistence: Save the entire batch to the repository.
	if len(allAnalyses) > 0 {
		if err := a.repository.StoreBatch(allAnalyses); err != nil {
			return fmt.Errorf("failed to store batch in repository: %w", err)
		}
		a.log.Info("Batch stored successfully", "count", len(allAnalyses))
	}

	return nil
}

// buildFinalAnalysis maps and merges raw file events with specialized analysis results.
// It acts as a decorator that enriches the initial metadata (path, mimetype)
// with deeper insights like extracted titles, authors, or sentiment/toxicity scores.
func (a *AnalysisSink) buildFinalAnalysis(evt event.FileAnalyse, resp specialist.AnalysisResponse) storage.Analysis {
	driveId, _ := uuid.Parse(evt.DriveID)

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
		Payload: evt,
		Version: uuid.New(),
	}

	for metric, res := range resp.Results {
		switch data := res.OneOf.(type) {
		case specialist.DocumentData:
			if data.Title != "" {
				analysis.Summary = data.Title
			}
			if data.Author != "" {
				analysis.Tags = append(analysis.Tags, "author:"+data.Author)
			}
		case specialist.Score:
			analysis.Scores[metric] = data.Score
			analysis.Tags = append(analysis.Tags, data.Label)
		}
	}

	return analysis
}
