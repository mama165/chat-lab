package storage

import (
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
)

// SetupTestDB initializes a temporary Badger instance for testing
func SetupTestDB(t *testing.T) (*badger.DB, func()) {
	opts := badger.DefaultOptions("").WithInMemory(true).WithLogger(nil)
	db, err := badger.Open(opts)
	require.NoError(t, err)

	return db, func() {
		db.Close()
	}
}

func TestFileTaskRepository_EnqueueAndBatch(t *testing.T) {
	req := require.New(t)
	db, cleanup := SetupTestDB(t)
	defer cleanup()

	logger := slog.New(slog.NewTextHandler(os.Stdout, nil))
	repo := NewFileTaskRepository(db, logger)

	// --- Scenario 1: Basic Enqueue & Fetch ---
	task1 := FileTask{
		ID:        uuid.New().String(),
		Path:      "/tmp/file1.txt",
		Priority:  NORMAL,
		CreatedAt: time.Now(),
	}

	err := repo.EnqueueTask(task1)
	req.NoError(err)

	batch, err := repo.GetNextBatch(10)
	req.NoError(err)
	req.Len(batch, 1)
	req.Equal(task1.ID, batch[0].ID)

	// --- Scenario 2: Priority Ordering (HIGH before NORMAL) ---
	// Clear the DB state by simply creating new entries (or use a fresh DB)
	// We add a Normal task, then a High task.
	normalTask := FileTask{
		ID:        "normal-id",
		Priority:  NORMAL,
		CreatedAt: time.Now(),
	}
	highTask := FileTask{
		ID:        "high-id",
		Priority:  HIGH,
		CreatedAt: time.Now().Add(time.Minute), // Even if it's created later
	}

	req.NoError(repo.EnqueueTask(normalTask))
	req.NoError(repo.EnqueueTask(highTask))

	// Should fetch HIGH priority first
	orderedBatch, err := repo.GetNextBatch(5)
	req.NoError(err)

	// Depending on previous inserts, we look for our specific ones
	// The first one MUST be the highTask if we consider only these two
	req.Equal("high-id", orderedBatch[0].ID, "HIGH priority task must be first")
}

func TestFileTaskRepository_ChronologicalOrdering(t *testing.T) {
	req := require.New(t)
	db, cleanup := SetupTestDB(t)
	defer cleanup()

	repo := NewFileTaskRepository(db, slog.Default())

	// Given: Two tasks with same priority but different timestamps
	t1 := time.Date(2026, 1, 1, 10, 0, 0, 0, time.UTC)
	t2 := time.Date(2026, 1, 1, 11, 0, 0, 0, time.UTC)

	taskOld := FileTask{ID: "old", Priority: NORMAL, CreatedAt: t1}
	taskNew := FileTask{ID: "new", Priority: NORMAL, CreatedAt: t2}

	req.NoError(repo.EnqueueTask(taskNew))
	req.NoError(repo.EnqueueTask(taskOld))

	// When: Fetching batch
	batch, err := repo.GetNextBatch(10)

	// Then: Oldest should be first (FIFO)
	req.NoError(err)
	req.Equal("old", batch[0].ID)
	req.Equal("new", batch[1].ID)
}

func TestFileTaskRepository_BatchLimit(t *testing.T) {
	req := require.New(t)
	db, cleanup := SetupTestDB(t)
	defer cleanup()

	repo := NewFileTaskRepository(db, slog.Default())

	// Given: 10 tasks
	for i := 0; i < 10; i++ {
		req.NoError(repo.EnqueueTask(FileTask{
			ID:        uuid.New().String(),
			Priority:  NORMAL,
			CreatedAt: time.Now().Add(time.Duration(i) * time.Second),
		}))
	}

	// When: Fetching with limit 3
	batch, err := repo.GetNextBatch(3)

	// Then: Only 3 are returned
	req.NoError(err)
	req.Len(batch, 3)
}

func TestFileTaskRepository_MarkAsProcessing(t *testing.T) {
	req := require.New(t)
	db, cleanup := SetupTestDB(t)
	defer cleanup()

	repo := NewFileTaskRepository(db, slog.Default())

	task := FileTask{
		ID:        "unique-id-123",
		Priority:  HIGH,
		CreatedAt: time.Now(),
	}

	// 1. Enqueue
	req.NoError(repo.EnqueueTask(task))

	// 2. Mark as processing
	req.NoError(repo.MarkAsProcessing(task))

	// 3. Assert: Should NOT be in pending anymore
	pendingTasks, err := repo.GetNextBatch(10)
	req.NoError(err)
	for _, p := range pendingTasks {
		req.NotEqual(task.ID, p.ID, "Task should have been removed from pending")
	}

	// 4. Assert: Should be findable in processing prefix (manually check Badger)
	err = db.View(func(txn *badger.Txn) error {
		_, err := txn.Get([]byte("work:processing:unique-id-123"))
		return err
	})
	req.NoError(err, "Task should exist in processing prefix")
}
