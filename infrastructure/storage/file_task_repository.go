//go:generate go run go.uber.org/mock/mockgen -source=file_task_repository.go -destination=../../mocks/mock_file_task_repository.go -package=mocks
package storage

import (
	"chat-lab/domain/mimetypes"
	pb "chat-lab/proto/storage"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type Priority int

const (
	HIGH   Priority = 0
	NORMAL Priority = 1
)

// FileTask represents a unit of work for the specialists.
// It is stored in BadgerDB to ensure persistence and prevent OOM.
type FileTask struct {
	ID                string
	Path              string
	RawMimeType       string
	EffectiveMimeType mimetypes.MIME
	Size              uint64
	Priority          Priority
	CreatedAt         time.Time
	RetryCount        int
}
type IFileTaskRepository interface {
	EnqueueTask(task FileTask) error
	GetNextBatch(i int) (fileTasks []FileTask, err error)
	MarkAsProcessing(task FileTask) error
}

type FileTaskRepository struct {
	db  *badger.DB
	log *slog.Logger
}

func NewFileTaskRepository(db *badger.DB, log *slog.Logger) *FileTaskRepository {
	return &FileTaskRepository{
		db:  db,
		log: log,
	}
}

// EnqueueTask persists a new file transfer task into BadgerDB with a priority-based key.
func (f FileTaskRepository) EnqueueTask(task FileTask) error {
	key := fmt.Sprintf("work:pending:%d:%d:%s",
		task.Priority,
		task.CreatedAt.UnixNano(),
		task.ID)

	data, err := proto.Marshal(toPbFileTask(task))
	if err != nil {
		return err
	}

	return f.db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte(key), data)
	})
}

// GetNextBatch retrieves a specific number of pending tasks, ordered by priority and creation time.
func (f FileTaskRepository) GetNextBatch(limit int) ([]FileTask, error) {
	var tasks []FileTask
	prefix := []byte("work:pending:")

	// We use a View (read-only transaction) to iterate
	err := f.db.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.PrefetchValues = true // Performance: prefetch values since we process small batches
		opts.PrefetchSize = limit

		it := txn.NewIterator(opts)
		defer it.Close()

		// Seek starts at the first key matching the prefix (High Priority first due to your key design)
		for it.Seek(prefix); it.ValidForPrefix(prefix) && len(tasks) < limit; it.Next() {
			item := it.Item()

			err := item.Value(func(v []byte) error {
				var pbTask pb.FileTask
				if err := proto.Unmarshal(v, &pbTask); err != nil {
					return fmt.Errorf("failed to unmarshal task: %w", err)
				}
				tasks = append(tasks, fromPbFileTask(&pbTask))
				return nil
			})

			if err != nil {
				return err
			}
		}
		return nil
	})

	if err != nil {
		return nil, fmt.Errorf("error during batch fetch: %w", err)
	}

	return tasks, nil
}

// MarkAsProcessing moves a task from pending status to processing by updating its key prefix atomically.
func (f FileTaskRepository) MarkAsProcessing(task FileTask) error {
	// 1. Reconstruct the exact pending key used during Enqueue
	pendingKey := fmt.Sprintf("work:pending:%d:%d:%s",
		task.Priority,
		task.CreatedAt.UnixNano(),
		task.ID)

	// 2. Create the new processing key (simpler, we just need the ID here)
	processingKey := fmt.Sprintf("work:processing:%s", task.ID)

	// 3. Serialize the data
	data, err := proto.Marshal(toPbFileTask(task))
	if err != nil {
		return fmt.Errorf("failed to marshal task for processing: %w", err)
	}

	// 4. Atomic transaction: Delete + Set
	return f.db.Update(func(txn *badger.Txn) error {
		// Optimization: Check if the pending key still exists before deleting
		_, err := txn.Get([]byte(pendingKey))
		if errors.Is(err, badger.ErrKeyNotFound) {
			return fmt.Errorf("task %s is no longer pending", task.ID)
		}

		if err := txn.Delete([]byte(pendingKey)); err != nil {
			return err
		}

		return txn.Set([]byte(processingKey), data)
	})
}

func fromPbFileTask(p *pb.FileTask) FileTask {
	return FileTask{
		ID:                p.Id,
		Path:              p.Path,
		RawMimeType:       p.RawMimeType,
		EffectiveMimeType: mimetypes.ToMIME(p.EffectiveMimeType),
		Size:              p.Size,
		Priority:          Priority(p.Prior),
		CreatedAt:         p.CreatedAt.AsTime(),
		RetryCount:        int(p.RetryCount),
	}
}

func toPbFileTask(task FileTask) *pb.FileTask {
	return &pb.FileTask{
		Id:                task.ID,
		Path:              task.Path,
		RawMimeType:       task.RawMimeType,
		EffectiveMimeType: string(task.EffectiveMimeType),
		Size:              task.Size,
		Prior:             toPbPriority(task.Priority),
		CreatedAt:         timestamppb.New(task.CreatedAt),
		RetryCount:        int32(task.RetryCount),
	}
}

func toPbPriority(priority Priority) pb.Priority {
	switch priority {
	case HIGH:
		return pb.Priority_HIGH
	case NORMAL:
		return pb.Priority_NORMAL
	default:
		return pb.Priority_NORMAL
	}
}
