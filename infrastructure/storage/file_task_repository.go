//go:generate go run go.uber.org/mock/mockgen -source=file_task_repository.go -destination=../../mocks/mock_file_task_repository.go -package=mocks
package storage

import (
	pb "chat-lab/proto/storage"
	"fmt"
	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
	"log/slog"
	"time"
)

type Priority int

const (
	HIGH   Priority = 0
	NORMAL Priority = 1
)

// FileTask represents a unit of work for the specialists.
// It is stored in BadgerDB to ensure persistence and prevent OOM.
type FileTask struct {
	ID         string
	Path       string
	MimeType   string
	Size       uint64
	Priority   Priority
	CreatedAt  time.Time
	RetryCount int
}
type IFileTaskRepository interface {
	EnqueueTask(task FileTask) error
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

func toPbFileTask(task FileTask) *pb.FileTask {
	return &pb.FileTask{
		Id:         task.ID,
		Path:       task.Path,
		MimeType:   task.MimeType,
		Size:       task.Size,
		Prior:      toPbPriority(task.Priority),
		CreatedAt:  timestamppb.New(task.CreatedAt),
		RetryCount: int32(task.RetryCount),
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
