package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"context"
	"errors"
	"fmt"
	"log/slog"
	"os"
	"sync"
	"time"

	"github.com/samber/lo"
)

// FileJob represents a tracking unit for a file being processed by specialists.
type FileJob struct {
	DriveID           string
	TmpFilePath       string
	EffectiveMimeType mimetypes.MIME
	IsRunning         bool
	CreatedAt         time.Time
}

// FileTmpJobWorker manages the lifecycle of temporary file analysis.
// It acts as a gatekeeper, ensuring that files are sent to specialists (gRPC)
// while respecting system capacity limits (backpressure).
type FileTmpJobWorker struct {
	mu                     sync.Mutex
	coordinator            contract.SpecialistCoordinator
	log                    *slog.Logger
	maxFileToProcess       uint32
	processingFileCount    uint32
	fileTmpJobInterval     time.Duration
	eventChan              chan event.Event
	tmpFileLocationChan    chan domain.TmpFileLocation
	specialistResponseChan chan domain.SpecialistResponse
	jobs                   map[domain.FileID]FileJob
}

func NewFileTmpJobWorker(
	coordinator contract.SpecialistCoordinator,
	log *slog.Logger,
	maxFileToProcess uint32,
	fileTmpJobInterval time.Duration,
	eventChan chan event.Event,
	tmpFileLocationChan chan domain.TmpFileLocation,
	specialistResponseChan chan domain.SpecialistResponse) *FileTmpJobWorker {
	return &FileTmpJobWorker{
		coordinator:            coordinator,
		log:                    log,
		maxFileToProcess:       maxFileToProcess,
		processingFileCount:    0,
		fileTmpJobInterval:     fileTmpJobInterval,
		eventChan:              eventChan,
		tmpFileLocationChan:    tmpFileLocationChan,
		specialistResponseChan: specialistResponseChan,
		jobs:                   make(map[domain.FileID]FileJob),
	}
}

// Run starts the main worker loop. It handles new file arrivals, specialist responses,
// and periodic job processing based on the configured interval.
func (w *FileTmpJobWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.fileTmpJobInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case tmpFileLocation := <-w.tmpFileLocationChan:
			w.addJob(tmpFileLocation)
		case <-ticker.C:
			w.processJobs(ctx)
		case response := <-w.specialistResponseChan:
			if err := w.handleSpecialistResponse(ctx, response); err != nil {
				return err
			}
		}
	}
}

// addJob registers a new file to be analyzed in the local memory map if it doesn't already exist.
func (w *FileTmpJobWorker) addJob(tmpFileLocation domain.TmpFileLocation) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if _, ok := w.jobs[tmpFileLocation.FileID]; !ok {
		job := FileJob{
			DriveID:           tmpFileLocation.DriveID,
			TmpFilePath:       tmpFileLocation.TmpFilePath,
			EffectiveMimeType: tmpFileLocation.EffectiveMimeType,
			IsRunning:         false,
			CreatedAt:         time.Now().UTC(),
		}
		w.jobs[tmpFileLocation.FileID] = job
		return
	}
	w.log.Debug("tmp file is already in queue", "path", tmpFileLocation.TmpFilePath)
}

// processJobs iterates over pending files and broadcasts analysis requests to specialists
// as long as the system has remaining capacity.
func (w *FileTmpJobWorker) processJobs(ctx context.Context) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if len(w.jobs) == 0 {
		w.log.Debug("No tmp file to send to specialist, waiting for next job...")
		return
	}
	nbrOfFile := w.maxFileToProcess - w.processingFileCount
	if nbrOfFile <= 0 {
		w.log.Debug("maximum number of file processing capacity reached",
			"processingFileCount", w.processingFileCount, "maxFileToProcess", w.maxFileToProcess)
		return
	}
	for fileID, fileJob := range w.jobs {
		if nbrOfFile == 0 {
			break
		}
		if fileJob.IsRunning {
			w.log.Debug("file is already processed by specialists", "fileID", fileID, "path", fileJob.TmpFilePath)
			continue
		}
		request := domain.SpecialistRequest{
			FileID:            fileID,
			Path:              fileJob.TmpFilePath,
			EffectiveMimeType: fileJob.EffectiveMimeType,
		}
		w.processingFileCount++ // Atomic is not needed (mutex can be used)
		if err := w.coordinator.Broadcast(ctx, request); err != nil {
			w.log.Error("Error while calling specialists", "err", err)
		}
		job := w.jobs[fileID]
		job.IsRunning = true
		w.jobs[fileID] = job
		nbrOfFile--
	}
}

// removeJob clears the file from the tracking map and returns the original DriveID.
// It also decrements the active processing counter.
func (w *FileTmpJobWorker) removeJob(resp domain.SpecialistResponse) (string, string, bool) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.log.Debug("specialist processed file", "FileID", resp.FileID)
	job, ok := w.jobs[resp.FileID]
	delete(w.jobs, resp.FileID)
	if w.processingFileCount > 0 {
		w.processingFileCount-- // Atomic is not needed (mutex can be used)
	}
	if !ok {
		return "", "", false
	}
	return job.DriveID, job.TmpFilePath, true
}

// handleSpecialistResponse processes the results from specialists, cleans up the local job tracking,
// and forwards the enriched data to the global event system.
func (w *FileTmpJobWorker) handleSpecialistResponse(ctx context.Context, response domain.SpecialistResponse) error {
	driveID, filePath, ok := w.removeJob(response)
	if !ok {
		w.log.Error("driveID or filepath should not be empty", "fileID", response.FileID, "path", filePath)
	}
	if err := w.deleteFile(filePath); err != nil {
		w.log.Error("unable to delete tmp file", "error", err)
	}
	select {
	case <-ctx.Done():
		return ctx.Err()
	case w.eventChan <- toEvent(driveID, response):
	}
	return nil
}

// deleteFile removes the temporary file from the disk.
// It uses os.IsNotExist to gracefully handle cases where the file might
// have been already removed, avoiding unnecessary error noise.
func (w *FileTmpJobWorker) deleteFile(path string) error {
	w.log.Debug("attempting to delete tmp file", "path", path)

	err := os.Remove(path)
	if err != nil {
		if errors.Is(err, os.ErrNotExist) {
			w.log.Warn("tmp file already gone", "path", path)
			return nil
		}
		return fmt.Errorf("failed to remove tmp file: %w", err)
	}
	w.log.Debug("tmp file deleted successfully", "path", path)
	return nil
}

func toEvent(driveID string, resp domain.SpecialistResponse) event.Event {
	return event.Event{
		Type:      event.AnalysisSegmentType,
		CreatedAt: time.Now().UTC(),
		Payload: event.AnalysisSegment{
			DriveID: driveID,
			FileID:  resp.FileID,
			Metrics: toAnalysisMetric(resp.Res),
		},
	}
}

func toAnalysisMetric(results []domain.SpecialistResult) []event.AnalysisMetric {
	return lo.Map(results, func(item domain.SpecialistResult, _ int) event.AnalysisMetric {
		return event.AnalysisMetric{
			ID:            item.ID,
			Resp:          item.Resp,
			SpecialistErr: item.SpecialistErr,
		}
	})
}
