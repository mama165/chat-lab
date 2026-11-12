package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/observability"
	"context"
	"log/slog"
	"time"

	"github.com/samber/lo"
)

// FileAnalysisWorker sends scanned files to the specialists and stores what they return.
// Each worker handles one file at a time, reading it straight from its location on
// disk: the number of workers is the number of files analyzed concurrently.
type FileAnalysisWorker struct {
	log           *slog.Logger
	coordinator   contract.SpecialistCoordinator
	analysisSink  contract.EventSink[contract.FileAnalyzerEvent]
	monitoring    *observability.MonitoringManager
	jobChan       <-chan domain.AnalysisJob
	telemetryChan chan event.Event
}

func NewFileAnalysisWorker(
	log *slog.Logger,
	coordinator contract.SpecialistCoordinator,
	analysisSink contract.EventSink[contract.FileAnalyzerEvent],
	monitoring *observability.MonitoringManager,
	jobChan <-chan domain.AnalysisJob,
	telemetryChan chan event.Event,
) *FileAnalysisWorker {
	return &FileAnalysisWorker{
		log:           log,
		coordinator:   coordinator,
		analysisSink:  analysisSink,
		monitoring:    monitoring,
		jobChan:       jobChan,
		telemetryChan: telemetryChan,
	}
}

func (w *FileAnalysisWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case job, ok := <-w.jobChan:
			if !ok {
				return nil
			}
			w.analyze(ctx, job)
		}
	}
}

// analyze runs one file through the specialists and merges their results into
// the record created at scan time. A failure only affects that file.
func (w *FileAnalysisWorker) analyze(ctx context.Context, job domain.AnalysisJob) {
	results, err := w.coordinator.Analyze(ctx, domain.SpecialistRequest{
		FileID:            job.FileID,
		Path:              job.Path,
		EffectiveMimeType: job.EffectiveMimeType,
	})
	if err != nil {
		if ctx.Err() == nil {
			w.monitoring.IncrErrorCount()
			w.log.Error("File analysis failed", "path", job.Path, "error", err)
		}
		return
	}
	if len(results) == 0 {
		return
	}
	w.monitoring.IncrMasterBytes(job.Size)

	segment := event.AnalysisSegment{
		DriveID: job.DriveID,
		FileID:  job.FileID,
		Metrics: lo.Map(results, func(item domain.SpecialistResult, _ int) event.AnalysisMetric {
			return event.AnalysisMetric{
				ID:            item.ID,
				Resp:          item.Resp,
				SpecialistErr: item.SpecialistErr,
			}
		}),
	}
	if err := w.analysisSink.Consume(ctx, segment); err != nil {
		w.monitoring.IncrErrorCount()
		w.log.Error("Unable to store specialist results", "path", job.Path, "error", err)
		return
	}

	// Telemetry is best effort: never block the analysis on it.
	select {
	case w.telemetryChan <- event.Event{
		Type:      event.AnalysisSegmentType,
		CreatedAt: time.Now().UTC(),
		Payload:   segment,
	}:
	default:
	}
}
