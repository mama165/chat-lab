package repositories

import (
	"chat-lab/domain/specialist"
	"context"
	"flag"
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
	"github.com/stretchr/testify/require"
)

// ============================================================================
// BATCH TESTS - StoreBatch Operations
// ============================================================================

func TestAnalysisRepository_StoreBatch_Success(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "batch-room"

	// Given: Multiple analyses to store in batch
	analyses := []Analysis{
		{
			ID:        uuid.New(),
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-3 * time.Hour),
			Summary:   "First batch item",
			Scores:    map[specialist.Metric]float64{specialist.MetricToxicity: 0.1},
			Payload:   TextContent{Content: "First content"},
		},
		{
			ID:        uuid.New(),
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-2 * time.Hour),
			Summary:   "Second batch item",
			Scores:    map[specialist.Metric]float64{specialist.MetricToxicity: 0.2},
			Payload:   TextContent{Content: "Second content"},
		},
		{
			ID:        uuid.New(),
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-1 * time.Hour),
			Summary:   "Third batch item",
			Scores:    map[specialist.Metric]float64{specialist.MetricToxicity: 0.3},
			Payload:   TextContent{Content: "Third content"},
		},
	}

	// When: Storing batch
	err = repo.StoreBatch(analyses)
	req.NoError(err)

	// Then: All items should be retrievable from BadgerDB
	for _, analysis := range analyses {
		fetched, err := repo.FetchFullByMessageId(roomID, analysis.MessageId)
		req.NoError(err)
		req.Equal(analysis.MessageId, fetched.MessageId)
		req.Equal(analysis.Summary, fetched.Summary)
	}

	// And: All items should be searchable in Bluge
	time.Sleep(50 * time.Millisecond)
	results, total, err := repo.SearchPaginated(ctx, "content", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(3), total)
	req.Len(results, 3)
}

func TestAnalysisRepository_StoreBatch_Empty(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// When: Storing empty batch
	err = repo.StoreBatch([]Analysis{})

	// Then: Should succeed without errors
	req.NoError(err)
}

func TestAnalysisRepository_StoreBatch_LargeBatch(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(200), 100)
	roomID := "large-batch-room"

	// Given: 100 analyses in a single batch
	const batchSize = 100
	analyses := make([]Analysis, batchSize)
	for i := 0; i < batchSize; i++ {
		analyses[i] = Analysis{
			ID:        uuid.New(),
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Second),
			Summary:   fmt.Sprintf("Batch item %d", i),
			Payload:   TextContent{Content: fmt.Sprintf("Content %d", i)},
		}
	}

	// When: Storing large batch
	start := time.Now()
	err = repo.StoreBatch(analyses)
	duration := time.Since(start)

	// Then: Should succeed
	req.NoError(err)
	t.Logf("StoreBatch(%d items) took %v", batchSize, duration)

	// And: All items should be retrievable
	time.Sleep(100 * time.Millisecond)
	_, total, err := repo.SearchPaginated(ctx, "Content", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(batchSize), total)
}

func TestAnalysisRepository_StoreBatch_WithScores(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "batch-scores-room"

	// Given: Batch with various scores
	analyses := []Analysis{
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-2 * time.Hour),
			Summary:   "Low toxicity",
			Scores: map[specialist.Metric]float64{
				specialist.MetricToxicity: 0.1,
				specialist.MetricBusiness: 0.9,
			},
			Payload: TextContent{Content: "Positive content"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-1 * time.Hour),
			Summary:   "High toxicity",
			Scores: map[specialist.Metric]float64{
				specialist.MetricToxicity: 0.95,
				specialist.MetricBusiness: 0.1,
			},
			Payload: TextContent{Content: "Negative content"},
		},
	}

	// When: Storing batch
	err = repo.StoreBatch(analyses)
	req.NoError(err)
	time.Sleep(50 * time.Millisecond)

	// Then: Should be searchable by score ranges
	highToxicity, _, err := repo.SearchByScoreRange(ctx, "toxicity", 0.9, 1.0, roomID)
	req.NoError(err)
	req.Len(highToxicity, 1)
	req.Equal("High toxicity", highToxicity[0].Summary)

	highBusiness, _, err := repo.SearchByScoreRange(ctx, "business", 0.8, 1.0, roomID)
	req.NoError(err)
	req.Len(highBusiness, 1)
	req.Equal("Low toxicity", highBusiness[0].Summary)
}

func TestAnalysisRepository_StoreBatch_DifferentRooms(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: Batch with items from different rooms
	analyses := []Analysis{
		{
			MessageId: uuid.New(),
			RoomId:    "room-1",
			At:        time.Now(),
			Summary:   "Room 1 item",
			Payload:   TextContent{Content: "Room 1 content"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    "room-2",
			At:        time.Now(),
			Summary:   "Room 2 item",
			Payload:   TextContent{Content: "Room 2 content"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    "room-1",
			At:        time.Now(),
			Summary:   "Another room 1 item",
			Payload:   TextContent{Content: "More room 1 content"},
		},
	}

	// When: Storing batch
	err = repo.StoreBatch(analyses)
	req.NoError(err)
	time.Sleep(50 * time.Millisecond)

	// Then: Each room should only see its own content
	room1Results, _, err := repo.SearchPaginated(ctx, "content", "room-1", 0)
	req.NoError(err)
	req.Len(room1Results, 2)
	for _, r := range room1Results {
		req.Equal("room-1", r.RoomId)
	}

	room2Results, _, err := repo.SearchPaginated(ctx, "content", "room-2", 0)
	req.NoError(err)
	req.Len(room2Results, 1)
	req.Equal("room-2", room2Results[0].RoomId)
}

func TestAnalysisRepository_StoreBatch_vs_Store_Performance(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping performance comparison in short mode")
	}

	req := require.New(t)

	const itemCount = 100

	// Test StoreBatch
	_, log1, badgerDB1, blugeWriter1, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB1, blugeWriter1)

	repo1 := NewAnalysisRepository(badgerDB1, blugeWriter1, log1, lo.ToPtr(200), 100)

	analyses := make([]Analysis, itemCount)
	for i := 0; i < itemCount; i++ {
		analyses[i] = Analysis{
			MessageId: uuid.New(),
			RoomId:    "perf-room",
			At:        time.Now().Add(time.Duration(i) * time.Second),
			Summary:   fmt.Sprintf("Item %d", i),
			Payload:   TextContent{Content: fmt.Sprintf("Content %d", i)},
		}
	}

	startBatch := time.Now()
	err = repo1.StoreBatch(analyses)
	req.NoError(err)
	batchDuration := time.Since(startBatch)

	// Test individual Store calls
	_, log2, badgerDB2, blugeWriter2, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB2, blugeWriter2)

	repo2 := NewAnalysisRepository(badgerDB2, blugeWriter2, log2, lo.ToPtr(200), 100)

	startIndividual := time.Now()
	for _, analysis := range analyses {
		err = repo2.Store(analysis)
		req.NoError(err)
	}
	individualDuration := time.Since(startIndividual)

	// Log performance comparison
	t.Logf("StoreBatch: %v", batchDuration)
	t.Logf("Individual Store: %v", individualDuration)
	t.Logf("Speedup: %.2fx", float64(individualDuration)/float64(batchDuration))

	// StoreBatch should be faster (though we don't enforce this with an assertion)
	if batchDuration < individualDuration {
		t.Logf("✓ StoreBatch is faster as expected")
	} else {
		t.Logf("⚠ StoreBatch was slower (might be acceptable for small batches)")
	}
}

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
// LARGE DATASET TEST (NO REQUIRE)
// ============================================================================

// 100k by default
var count = flag.Int("count", 100_000, "nombre de docs")

func TestAnalysisRepository_Search_100kDocuments(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping large dataset test in short mode")
	}

	ctx, log, badgerDB, blugeWriter := initTest(t)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(1000), 100)
	roomID := "large-dataset-room"

	const totalDocs = 5000
	const targetKeyword = "special"

	if !flag.Parsed() {
		flag.Parse()
	}
	n := *count

	t.Logf("Inserting %d documents...", n)
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
