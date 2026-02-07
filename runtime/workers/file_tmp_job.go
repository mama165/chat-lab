package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"context"
	"log/slog"
	"sync"
	"time"
)

type FileJob struct {
	FileID      domain.FileID
	TmpFilePath string
	MimeType    string
	CreatedAt   time.Time
}

type FileTmpJobWorker struct {
	mu                  sync.Mutex
	coordinator         contract.SpecialistCoordinator
	log                 *slog.Logger
	maxFileToProcess    int
	fileTmpJobInterval  time.Duration
	tmpFileLocationChan chan domain.TmpFileLocation
	fileIDs             map[domain.FileID]struct{}
	jobs                []FileJob
}

func NewFileTmpJobWorker(
	coordinator contract.SpecialistCoordinator,
	log *slog.Logger,
	maxFileToProcess int,
	fileTmpJobInterval time.Duration,
	tmpFileLocationChan chan domain.TmpFileLocation,
) *FileTmpJobWorker {
	return &FileTmpJobWorker{
		coordinator:         coordinator,
		log:                 log,
		maxFileToProcess:    maxFileToProcess,
		fileTmpJobInterval:  fileTmpJobInterval,
		tmpFileLocationChan: tmpFileLocationChan,
		fileIDs:             make(map[domain.FileID]struct{}),
		jobs:                make([]FileJob, 0),
	}
}

func (w *FileTmpJobWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.fileTmpJobInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case tmpFileLocation := <-w.tmpFileLocationChan:
			w.mu.Lock()
			if _, ok := w.fileIDs[tmpFileLocation.FileID]; !ok {
				job := FileJob{
					FileID:      tmpFileLocation.FileID,
					TmpFilePath: tmpFileLocation.TmpFilePath,
					MimeType:    tmpFileLocation.MimeType,
					CreatedAt:   time.Now().UTC(),
				}
				w.jobs = append(w.jobs, job)
			}
			w.mu.Unlock()
		case <-ticker.C:
			w.mu.Lock()
			if len(w.jobs) == 0 {
				w.log.Debug("No tmp file to send to specialist, waiting for next job...")
				continue
			}
			length := len(w.jobs)
			if len(w.jobs) < w.maxFileToProcess {
				length = w.maxFileToProcess
			}
			tmpFiles := w.jobs[:length]

			for _, _ = range tmpFiles {
				w.coordinator.Broadcast(ctx, domain.SpecialistRequest{})
			}
			w.mu.Unlock()
		}
	}
}
