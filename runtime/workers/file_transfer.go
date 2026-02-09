package workers

import (
	"chat-lab/domain"
	"chat-lab/infrastructure/storage"
	"context"
	"log/slog"
	"time"
)

type FileTransferWorker struct {
	requestChan          chan<- domain.FileDownloaderRequest
	filesTaskRepository  storage.IFileTaskRepository
	log                  *slog.Logger
	fileTransferInterval time.Duration
	pendingFileBatchSize int
}

func NewFileTransferWorker(
	requestChan chan<- domain.FileDownloaderRequest,
	filesTaskRepository storage.IFileTaskRepository,
	log *slog.Logger,
	fileTransferInterval time.Duration,
	pendingFileBatchSize int,
) *FileTransferWorker {
	return &FileTransferWorker{
		requestChan:          requestChan,
		filesTaskRepository:  filesTaskRepository,
		log:                  log,
		fileTransferInterval: fileTransferInterval,
		pendingFileBatchSize: pendingFileBatchSize,
	}
}

// Run start a loop that polls Badger for new tasks.
// It sends requests to the gRPC worker through the send-only channel.
func (w *FileTransferWorker) Run(ctx context.Context) error {
	w.log.Info("Starting pending file poller", "started_at", time.Now())

	ticker := time.NewTicker(w.fileTransferInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.log.Info("Stopping pending file poller")
			return ctx.Err()
		case <-ticker.C:
			tasks, err := w.filesTaskRepository.GetNextBatch(w.pendingFileBatchSize)
			if err != nil {
				w.log.Error("Failed to fetch next batch", "error", err)
				continue
			}

			if len(tasks) == 0 {
				w.log.Debug("No tasks have been found")
				continue
			}

			w.log.Debug("tasks have been found", "count", len(tasks))

			for _, task := range tasks {
				if err := w.filesTaskRepository.MarkAsProcessing(task); err != nil {
					w.log.Error("Failed to mark task as processing", "id", task.ID, "error", err)
					continue
				}

				// This will block if the gRPC worker is overwhelmed (Natural Backpressure)
				w.requestChan <- domain.FileDownloaderRequest{
					DriveID: task.DriveID,
					FileID:  domain.FileID(task.ID),
					Path:    task.Path,
				}

				w.log.Debug("Task dispatched to gRPC worker", "id", task.ID)
			}
		}
	}
}
