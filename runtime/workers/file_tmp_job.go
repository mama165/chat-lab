package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"context"
	"log/slog"
	"sync"
	"time"
)

type FileJob struct {
	TmpFilePath       string
	EffectiveMimeType mimetypes.MIME
	IsRunning         bool
	CreatedAt         time.Time
}

type FileTmpJobWorker struct {
	mu                     sync.Mutex
	coordinator            contract.SpecialistCoordinator
	log                    *slog.Logger
	maxFileToProcess       uint32
	processingFileCount    uint32
	fileTmpJobInterval     time.Duration
	tmpFileLocationChan    chan domain.TmpFileLocation
	specialistResponseChan chan domain.SpecialistResponse
	jobs                   map[domain.FileID]FileJob
}

func NewFileTmpJobWorker(
	coordinator contract.SpecialistCoordinator,
	log *slog.Logger,
	maxFileToProcess uint32,
	fileTmpJobInterval time.Duration,
	tmpFileLocationChan chan domain.TmpFileLocation,
	specialistResponseChan chan domain.SpecialistResponse) *FileTmpJobWorker {
	return &FileTmpJobWorker{
		coordinator:            coordinator,
		log:                    log,
		maxFileToProcess:       maxFileToProcess,
		processingFileCount:    0,
		fileTmpJobInterval:     fileTmpJobInterval,
		tmpFileLocationChan:    tmpFileLocationChan,
		specialistResponseChan: specialistResponseChan,
		jobs:                   make(map[domain.FileID]FileJob),
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
			w.addJob(tmpFileLocation)
		case specialistResponse := <-w.specialistResponseChan:
			w.removeJob(specialistResponse)
			// TODO Send a specific event, will be consume by AnalysisSink and update the record
		case <-ticker.C:
			w.processJobs(ctx)
		}
	}
}

func (w *FileTmpJobWorker) removeJob(resp domain.SpecialistResponse) {
	w.mu.Lock()
	defer w.mu.Unlock()
	w.log.Debug("specialist processed file", "FileID", resp.FileID)
	delete(w.jobs, resp.FileID)
	if w.processingFileCount > 0 {
		w.processingFileCount-- // Atomic is not needed (mutex can be used)
	}
}

func (w *FileTmpJobWorker) addJob(tmpFileLocation domain.TmpFileLocation) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if _, ok := w.jobs[tmpFileLocation.FileID]; !ok {
		job := FileJob{
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
