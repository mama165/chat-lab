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
	"strings"
	"sync"
	"time"

	"github.com/google/uuid"
)

type AnalysisSink struct {
	mu                 sync.Mutex
	timer              *time.Timer
	analysisRepository storage.IAnalysisRepository
	// jobChan receives the files to analyze once their metadata is stored.
	// It is bounded: when the specialists are slower than the scan, sending
	// blocks, and the pressure goes back up to the scanner.
	jobChan           chan<- domain.AnalysisJob
	log               *slog.Logger
	events            []event.FileAnalyse
	maxAnalyzedEvent  int
	bufferTimeout     time.Duration
	specialistTimeout time.Duration
	// stop is closed by Close to release senders blocked on jobChan at shutdown.
	stop     chan struct{}
	stopOnce sync.Once
}

func NewAnalysisSink(
	analysisRepository storage.IAnalysisRepository,
	jobChan chan<- domain.AnalysisJob,
	log *slog.Logger,
	maxAnalyzedEvent int,
	bufferTimeout time.Duration,
	specialistTimeout time.Duration,
) *AnalysisSink {
	return &AnalysisSink{
		analysisRepository: analysisRepository,
		jobChan:            jobChan,
		log:                log,
		maxAnalyzedEvent:   maxAnalyzedEvent,
		bufferTimeout:      bufferTimeout,
		specialistTimeout:  specialistTimeout,
		stop:               make(chan struct{}),
	}
}

// Close releases any goroutine waiting to hand a file over for analysis.
// It is safe to call several times.
func (a *AnalysisSink) Close() {
	a.stopOnce.Do(func() { close(a.stop) })
}

// Consume implements the EventSink interface.
func (a *AnalysisSink) Consume(ctx context.Context, e contract.FileAnalyzerEvent) error {
	switch evt := e.(type) {
	case event.FileAnalyse:
		return a.handleNewFile(ctx, evt)

	case event.AnalysisSegment:
		return a.handleAnalysisSegment(evt)

	default:
		return nil
	}
}

func (a *AnalysisSink) handleNewFile(ctx context.Context, e event.FileAnalyse) error {
	a.mu.Lock()
	// 2. State update: Append the event to the current slice
	a.events = append(a.events, e)

	// 3. Timer management: if this is the first event of a new batch,
	// start a background timer to ensure data is not stuck if the throughput is low.
	// We only start it if no other timer is currently running (timer == nil).
	if len(a.events) == 1 && a.timer == nil {
		a.timer = time.AfterFunc(a.bufferTimeout, func() {
			// No caller to inherit a context from: Close() is what releases this flush.
			if err := a.flush(context.Background()); err != nil {
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
			// Keep the text of every page: this is what makes the document searchable.
			if details, ok := analysis.Payload.(storage.FileDetails); ok && len(data.Pages) > 0 {
				pages := make([]string, 0, len(data.Pages))
				for _, page := range data.Pages {
					pages = append(pages, page.Content)
				}
				details.Content = strings.Join(pages, "\n")
				analysis.Payload = details
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

	return a.processAndStore(ctx, batchEvents)
}

// processAndStore persists the basic file metadata, then hands the files over
// to the specialists for the heavy analysis (audio, PDF, image).
// The order matters: a file is queued only once its record exists, because the
// specialist results are merged into that record.
func (a *AnalysisSink) processAndStore(ctx context.Context, events []event.FileAnalyse) error {
	if len(events) == 0 {
		return nil
	}

	allAnalyses := make([]storage.Analysis, 0, len(events))
	for _, evt := range events {
		allAnalyses = append(allAnalyses, a.buildFinalAnalysis(evt))
	}

	if err := a.analysisRepository.StoreBatch(allAnalyses); err != nil {
		return err
	}

	for _, evt := range events {
		if _, ok := mimetypes.IsAuthorized(evt.RawMimeType); !ok {
			continue
		}
		job := domain.AnalysisJob{
			DriveID:           evt.DriveID,
			FileID:            domain.FileID(evt.Id.String()),
			Path:              evt.Path,
			Size:              evt.Size,
			EffectiveMimeType: evt.EffectiveMimeType,
		}
		select {
		case a.jobChan <- job:
		case <-ctx.Done():
			return ctx.Err()
		case <-a.stop:
			return nil
		}
	}
	return nil
}

// buildFinalAnalysis maps a scanned file to its initial record: the metadata
// known from the file system. The specialist results are merged later by
// handleAnalysisSegment.
// The record is stored under the drive ID, which is also the namespace used to
// fetch it back for enrichment.
func (a *AnalysisSink) buildFinalAnalysis(evt event.FileAnalyse) storage.Analysis {
	return storage.Analysis{
		ID:        evt.Id,
		EntityId:  evt.Id,
		Namespace: evt.DriveID,
		At:        evt.ScannedAt,
		Summary:   evt.Path,
		Tags:      []string{evt.RawMimeType, evt.SourceType},
		Scores:    make(map[domain.Metric]float64),
		Version:   uuid.New(),
		Payload: storage.FileDetails{
			Filename: evt.Path,
			MimeType: evt.RawMimeType,
			Size:     evt.Size,
		},
	}
}
