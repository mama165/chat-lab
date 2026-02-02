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
	w.log.Debug("Starting pending file poller", "started_at", time.Now())

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
				continue
			}

			for _, task := range tasks {
				if err := w.filesTaskRepository.MarkAsProcessing(task); err != nil {
					w.log.Error("Failed to mark task as processing", "id", task.ID, "error", err)
					continue
				}

				// This will block if the gRPC worker is overwhelmed (Natural Backpressure)
				w.requestChan <- domain.FileDownloaderRequest{
					FileID: domain.FileID(task.ID),
					Path:   task.Path,
				}

				w.log.Debug("Task dispatched to gRPC worker", "id", task.ID)
			}
		}
	}
}
