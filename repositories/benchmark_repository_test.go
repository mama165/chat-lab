package repositories

import (
	"context"
	"fmt"
	"log/slog"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/mama165/sdk-go/db"
	"github.com/samber/lo"
)

// ============================================================================
// CONCURRENCY TESTS (SANS REQUIRE)
// ============================================================================

func TestAnalysisRepository_ConcurrentStores(t *testing.T) {
	ctx, log, badgerDB, blugeWriter := initTest(t)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 50)
	roomID := "concurrent-room"

	const (
		numGoroutines    = 10
		writesPerRoutine = 50
		totalWrites      = numGoroutines * writesPerRoutine
	)

	var wg sync.WaitGroup
	var successCount atomic.Int32
	var errorCount atomic.Int32
	storedIDs := make([]uuid.UUID, totalWrites)

	startTime := time.Now()
	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(routineID int) {
			defer wg.Done()
			for j := 0; j < writesPerRoutine; j++ {
				msgID := uuid.New()
				idx := routineID*writesPerRoutine + j
				storedIDs[idx] = msgID

				analysis := Analysis{
					MessageId: msgID,
					RoomId:    roomID,
					At:        time.Now().UTC(),
					Summary:   fmt.Sprintf("Routine %d - Message %d", routineID, j),
					Payload:   TextContent{Content: fmt.Sprintf("Concurrent write test content %d-%d", routineID, j)},
				}

				if err := repo.Store(analysis); err != nil {
					t.Logf("Store error in routine %d: %v", routineID, err)
					errorCount.Add(1)
				} else {
					successCount.Add(1)
				}
			}
		}(i)
	}

	wg.Wait()
	duration := time.Since(startTime)

	if successCount.Load() != int32(totalWrites) {
		t.Errorf("Expected %d successful stores, got %d", totalWrites, successCount.Load())
	}

	if err := repo.Flush(); err != nil {
		t.Fatalf("Flush failed: %v", err)
	}
	time.Sleep(100 * time.Millisecond)

	validCount := 0
	for _, msgID := range storedIDs {
		if _, err := repo.FetchFullByMessageId(roomID, msgID); err == nil {
			validCount++
		}
	}
	if validCount != totalWrites {
		t.Errorf("Only %d/%d documents retrievable from Badger", validCount, totalWrites)
	}

	_, total, err := repo.SearchPaginated(ctx, "Concurrent", roomID, 0)
	if err != nil {
		t.Fatalf("Search failed: %v", err)
	}
	if total != uint64(totalWrites) {
		t.Errorf("Search found %d documents, expected %d", total, totalWrites)
	}
	t.Logf("Performance: %.0f writes/sec", float64(totalWrites)/duration.Seconds())
}

// ============================================================================
// PERFORMANCE BENCHMARKS (SANS REQUIRE)
// ============================================================================

func BenchmarkAnalysisRepository_Hydration(b *testing.B) {
	_, _, badgerDB, blugeWriter := initTest(b)
	defer db.CleanupDB(badgerDB, blugeWriter)

	// On récupère le logger spécifiquement pour le benchmark
	log := slog.Default()
	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 100)
	roomID := "hydration-bench"

	messageIDs := make([]uuid.UUID, 1000)
	for i := 0; i < 1000; i++ {
		msgID := uuid.New()
		messageIDs[i] = msgID
		analysis := Analysis{
			MessageId: msgID,
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   "Hydration benchmark",
			Payload:   TextContent{Content: "test content"},
		}
		if err := repo.Store(analysis); err != nil {
			b.Fatal(err)
		}
	}
	repo.Flush()

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		msgID := messageIDs[i%len(messageIDs)]
		_, err := repo.FetchFullByMessageId(roomID, msgID)
		if err != nil {
			b.Fatal(err)
		}
	}
}

func BenchmarkAnalysisRepository_Store(b *testing.B) {
	_, log, badgerDB, blugeWriter := initTest(b)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 100)
	roomID := "store-bench"

	b.ResetTimer()
	for i := 0; i < b.N; i++ {
		analysis := Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   "Benchmark store",
			Payload:   TextContent{Content: "benchmark content"},
		}
		if err := repo.Store(analysis); err != nil {
			b.Fatal(err)
		}
		if i%100 == 0 {
			repo.Flush()
		}
	}
}

// ============================================================================
// LARGE DATASET TEST (SANS REQUIRE)
// ============================================================================

func TestAnalysisRepository_Search_100kDocuments(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping large dataset test in short mode")
	}

	ctx, log, badgerDB, blugeWriter := initTest(t)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(1000), 100)
	roomID := "large-dataset-room"

	const totalDocs = 100_000
	const targetKeyword = "special"

	t.Logf("Inserting %d documents...", totalDocs)
	for i := 0; i < totalDocs; i++ {
		content := "regular content"
		if i%20 == 0 {
			content = "special keyword"
		}

		analysis := Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Millisecond),
			Summary:   fmt.Sprintf("Doc %d", i),
			Payload:   TextContent{Content: content},
		}

		if err := repo.Store(analysis); err != nil {
			t.Fatalf("Store failed at index %d: %v", i, err)
		}

		if (i+1)%1000 == 0 {
			repo.Flush()
		}
	}
	repo.Flush()

	t.Log("Searching...")
	start := time.Now()
	results, total, err := repo.SearchPaginated(ctx, targetKeyword, roomID, 0)
	if err != nil {
		t.Fatal(err)
	}

	t.Logf("Search for 100k docs took %v. Found: %d", time.Since(start), total)
	if total != 5000 {
		t.Errorf("Expected 5000 matches (5%% of 100k), got %d", total)
	}
	if len(results) > 100 {
		t.Errorf("Expected max 100 results (page limit), got %d", len(results))
	}
}

// ============================================================================
// HELPERS (SANS REQUIRE)
// ============================================================================

func initTest(t testing.TB) (context.Context, *slog.Logger, *badger.DB, *bluge.Writer) {
	path := t.TempDir()
	// Usage de ta méthode SDK
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(path)
	if err != nil {
		t.Fatalf("Failed to setup test: %v", err)
	}
	return ctx, log, badgerDB, blugeWriter
}
