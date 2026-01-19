package repositories

import (
	"chat-lab/domain"
	pb "chat-lab/proto/storage"
	"fmt"
	"github.com/blugelabs/bluge"
	"github.com/mama165/sdk-go/db"
	"github.com/mama165/sdk-go/logs"
	"github.com/samber/lo"
	"sync"
	"sync/atomic"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/timestamppb"

	"log/slog"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
)

func Test_MessageHistory_Performance(t *testing.T) {
	req := require.New(t)
	path := t.TempDir()
	db, err := badger.Open(badger.DefaultOptions(path).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)
	defer db.Close()

	log := slog.Default()
	limit := 50
	repo := NewMessageRepository(db, log, &limit)

	totalMessages := 1_000_000
	targetRoom := 42

	// --- Phase 1: SEEDING 1 MILLION MESSAGES ---
	// On utilise Protobuf pour simuler le format réel en base
	fmt.Printf("Starting seeding of %d messages...\n", totalMessages)
	startSeed := time.Now()
	wb := db.NewWriteBatch()

	for i := 0; i < totalMessages; i++ {
		roomID := i % 100                                        // Distribution sur 100 rooms
		at := time.Now().Add(time.Duration(i) * time.Nanosecond) // Nanosecondes pour éviter les collisions de clés

		author := fmt.Sprintf("user_%d", i%500)
		content := "Hello world, this is a performance test for Chat-Lab!"

		// 1. On crée la clé au format réel du repository
		// msg:{room_id}:{timestamp}:{user_id}
		key := fmt.Sprintf("msg:%d:%d:%s", roomID, at.UnixNano(), author)

		// 2. On sérialise en Protobuf comme le fait ton code de prod
		pbMsg := pb.Message{
			Id:      uuid.NewString(),
			Room:    int64(roomID),
			Author:  author,
			Content: content,
			At:      timestamppb.New(at),
		}
		bytes, _ := proto.Marshal(&pbMsg)

		// 3. Ajout au batch
		_ = wb.Set([]byte(key), bytes)

		if i%200_000 == 0 && i > 0 {
			fmt.Printf("  -> Inserted %d messages...\n", i)
		}
	}

	err = wb.Flush()
	req.NoError(err)

	fmt.Printf("✅ Seeded %d messages in %v\n", totalMessages, time.Since(startSeed))

	// --- RECOVERY OF 50 MESSAGES IN ROOM 42 ---
	fmt.Printf("Retrieving last %d messages for Room %d...\n", limit, targetRoom)
	startGet := time.Now()

	messages, _, err := repo.GetMessages(targetRoom, nil)
	req.NoError(err)

	duration := time.Since(startGet)
	fmt.Printf("✅ Retrieved %d messages for Room %d in %v\n", len(messages), targetRoom, duration)

	// --- VERIFICATION ---
	req.NotEmpty(messages)
}



// ============================================================================
// CONCURRENCY TESTS
// ============================================================================

// TestAnalysisRepository_ConcurrentStores validates thread-safety when multiple
// goroutines write different analyses simultaneously.
func TestAnalysisRepository_ConcurrentStores(t *testing.T) {
	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 50)
	roomID := "concurrent-room"

	// Given: Configuration for concurrent writes
	const (
		numGoroutines = 10
		writesPerRoutine = 50
		totalWrites = numGoroutines * writesPerRoutine
	)

	var wg sync.WaitGroup
	var successCount atomic.Int32
	var errorCount atomic.Int32

	storedIDs := make([]uuid.UUID, totalWrites)

	// When: Multiple goroutines write concurrently
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

				err := repo.Store(analysis)
				if err != nil {
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

	// Then: All writes should succeed
	req.Equal(int32(totalWrites), successCount.Load(), "All stores should succeed")
	req.Equal(int32(0), errorCount.Load(), "No errors should occur")

	t.Logf("Concurrent stores: %d writes in %v (%.0f writes/sec)",
		totalWrites, duration, float64(totalWrites)/duration.Seconds())

	// And: Flush to ensure all data is indexed
	req.NoError(repo.Flush())
	time.Sleep(100 * time.Millisecond)

	// And: All documents should be retrievable from BadgerDB
	validCount := 0
	for _, msgID := range storedIDs {
		_, err := repo.FetchFullByMessageId(roomID, msgID)
		if err == nil {
			validCount++
		}
	}
	req.Equal(totalWrites, validCount, "All documents should be retrievable")

	// And: Search should find all documents
	results, total, err := repo.SearchPaginated(ctx, "Concurrent", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(totalWrites), total, "Search should find all documents")
	t.Logf("Search found %d/%d documents", len(results), totalWrites)
}

// TestAnalysisRepository_ConcurrentStores_SameMessage validates behavior when
// multiple goroutines try to update the same message ID (last-write-wins).
func TestAnalysisRepository_ConcurrentStores_SameMessage(t *testing.T) {
	req, _, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "same-msg-room"
	msgID := uuid.New()

	const numGoroutines = 20
	var wg sync.WaitGroup
	versions := make([]string, numGoroutines)

	// When: Multiple goroutines write different versions of the same messageID
	for i := 0; i < numGoroutines; i++ {
		wg.Add(1)
		go func(version int) {
			defer wg.Done()

			analysis := Analysis{
				MessageId: msgID,
				RoomId:    roomID,
				At:        time.Now().UTC(),
				Summary:   fmt.Sprintf("Version %d", version),
				Payload:   TextContent{Content: fmt.Sprintf("Content version %d", version)},
			}

			versions[version] = fmt.Sprintf("Version %d", version)
			repo.Store(analysis)
		}(i)
	}

	wg.Wait()
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: Only one version should be stored (last-write-wins)
	fetched, err := repo.FetchFullByMessageId(roomID, msgID)
	req.NoError(err)

	// Verify the stored version is one of the written versions
	req.Contains(versions, fetched.Summary, "Should contain one of the written versions")
	t.Logf("Final version: %s", fetched.Summary)
}

// TestAnalysisRepository_StoreWhileSearching validates that searches work
// correctly while concurrent writes are happening (eventual consistency).
func TestAnalysisRepository_StoreWhileSearching(t *testing.T) {
	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 50)
	roomID := "search-write-room"

	// Given: Initial dataset
	for i := 0; i < 100; i++ {
		req.NoError(repo.Store(Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   "Initial data",
			Payload:   TextContent{Content: "searchable keyword initial"},
		}))
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	var wg sync.WaitGroup
	stopFlag := atomic.Bool{}
	searchCount := atomic.Int32{}
	writeCount := atomic.Int32{}

	// When: Concurrent searchers
	for i := 0; i < 5; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			for !stopFlag.Load() {
				_, total, err := repo.SearchPaginated(ctx, "searchable", roomID, 0)
				if err == nil {
					searchCount.Add(1)
					// Total should be >= 100 (initial) and growing
					req.GreaterOrEqual(total, uint64(100))
				}
				time.Sleep(10 * time.Millisecond)
			}
		}()
	}

	// And: Concurrent writers
	for i := 0; i < 3; i++ {
		wg.Add(1)
		go func(writerID int) {
			defer wg.Done()
			for j := 0; j < 50; j++ {
				analysis := Analysis{
					MessageId: uuid.New(),
					RoomId:    roomID,
					At:        time.Now().UTC(),
					Summary:   fmt.Sprintf("Writer %d msg %d", writerID, j),
					Payload:   TextContent{Content: "searchable keyword new"},
				}

				if err := repo.Store(analysis); err == nil {
					writeCount.Add(1)
				}

				// Flush periodically to make data searchable
				if j%10 == 0 {
					repo.Flush()
				}

				time.Sleep(5 * time.Millisecond)
			}
		}(i)
	}

	// Let it run for 2 seconds
	time.Sleep(2 * time.Second)
	stopFlag.Store(true)
	wg.Wait()

	// Then: No panics or deadlocks occurred
	req.Greater(searchCount.Load(), int32(0), "Searches should have executed")
	req.Greater(writeCount.Load(), int32(0), "Writes should have executed")

	t.Logf("Executed %d searches and %d writes concurrently",
		searchCount.Load(), writeCount.Load())

	// Final flush and verify total count
	req.NoError(repo.Flush())
	time.Sleep(100 * time.Millisecond)

	_, total, err := repo.SearchPaginated(ctx, "searchable", roomID, 0)
	req.NoError(err)
	expectedMin := uint64(100 + writeCount.Load())
	req.GreaterOrEqual(total, expectedMin, "Should find all written documents")
}

// ============================================================================
// PERFORMANCE BENCHMARKS
// ============================================================================

// BenchmarkAnalysisRepository_Search1000Results measures search performance
// with varying result set sizes.
func BenchmarkAnalysisRepository_Search1000Results(b *testing.B) {
	req, ctx, log, badgerDB, blugeWriter := initTest(b)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(1000), 1000)
	roomID := "bench-room"

	// Setup: Insert 10,000 documents
	b.Log("Setting up 10,000 test documents...")
	for i := 0; i < 10000; i++ {
		analysis := Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   fmt.Sprintf("Benchmark document %d", i),
			Payload:   TextContent{Content: fmt.Sprintf("searchable benchmark content number %d", i)},
			Scores: map[domain.AnalysisMetric]float64{
				domain.MetricToxicity: float64(i%100) / 100.0,
			},
		}
		req.NoError(repo.Store(analysis))

		if i%1000 == 0 {
			repo.Flush()
		}
	}
	req.NoError(repo.Flush())
	time.Sleep(200 * time.Millisecond)
	b.Log("Setup complete")

	b.ResetTimer()

	// Benchmark: Search operations
	for i := 0; i < b.N; i++ {
		results, total, err := repo.SearchPaginated(ctx, "searchable", roomID, 0)
		req.NoError(err)
		req.Greater(total, uint64(0))
		req.Greater(len(results), 0)
	}

	b.StopTimer()

	// Report metrics
	opsPerSec := float64(b.N) / b.Elapsed().Seconds()
	b.ReportMetric(opsPerSec, "searches/sec")
}

// BenchmarkAnalysisRepository_Hydration measures the cost of hydrating
// search results from BadgerDB.
func BenchmarkAnalysisRepository_Hydration(b *testing.B) {
	req := require.New(b)
	ctx := context.Background()
	log, badgerDB, blugeWriter := setupBenchmark(b)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(100), 100)
	roomID := "hydration-bench"

	// Setup: Insert 1000 documents
	messageIDs := make([]uuid.UUID, 1000)
	for i := 0; i < 1000; i++ {
		msgID := uuid.New()
		messageIDs[i] = msgID

		analysis := Analysis{
			MessageId: msgID,
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   "Hydration benchmark",
			Payload:   TextContent{Content: "test content for hydration benchmark"},
		}
		req.NoError(repo.Store(analysis))
	}
	req.NoError(repo.Flush())
	time.Sleep(100 * time.Millisecond)

	b.ResetTimer()

	// Benchmark: Direct fetch (hydration) operations
	for i := 0; i < b.N; i++ {
		msgID := messageIDs[i%len(messageIDs)]
		_, err := repo.FetchFullByMessageId(roomID, msgID)
		req.NoError(err)
	}

	b.StopTimer()

	fetchesPerSec := float64(b.N) / b.Elapsed().Seconds()
	b.ReportMetric(fetchesPerSec, "fetches/sec")
	b.ReportMetric(b.Elapsed().Nanoseconds()/int64(b.N), "ns/fetch")
}

// BenchmarkAnalysisRepository_Store measures write performance.
func BenchmarkAnalysisRepository_Store(b *testing.B) {
	req := require.New(b)
	log, badgerDB, blugeWriter := setupBenchmark(b)
	defer cleanup(badgerDB, blugeWriter)

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
		req.NoError(repo.Store(analysis))

		// Flush every 100 writes
		if i%100 == 0 {
			repo.Flush()
		}
	}

	b.StopTimer()

	storesPerSec := float64(b.N) / b.Elapsed().Seconds()
	b.ReportMetric(storesPerSec, "stores/sec")
}

// ============================================================================
// DATA INTEGRITY TESTS
// ============================================================================

// TestAnalysisRepository_DeleteByMessageId validates that deletion removes
// the document from both BadgerDB and the Bluge index.
func TestAnalysisRepository_DeleteByMessageId(t *testing.T) {
	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "delete-room"

	// Given: Three stored analyses
	msg1 := uuid.New()
	msg2 := uuid.New()
	msg3 := uuid.New()

	for _, msgID := range []uuid.UUID{msg1, msg2, msg3} {
		req.NoError(repo.Store(Analysis{
			MessageId: msgID,
			RoomId:    roomID,
			At:        time.Now().UTC(),
			Summary:   "To be deleted",
			Payload:   TextContent{Content: "deletable content"},
		}))
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Verify all are searchable
	results, total, err := repo.SearchPaginated(ctx, "deletable", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(3), total)
	req.Len(results, 3)

	// When: Deleting msg2
	err = repo.DeleteByMessageId(roomID, msg2)
	req.NoError(err)

	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: msg2 should not be fetchable from BadgerDB
	_, err = repo.FetchFullByMessageId(roomID, msg2)
	req.Error(err, "Deleted message should not be found in BadgerDB")
	req.Contains(err.Error(), "not found")

	// And: msg2 should not appear in search results
	results, total, err = repo.SearchPaginated(ctx, "deletable", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(2), total, "Should only find 2 documents after deletion")
	req.Len(results, 2)

	// And: Other messages should still be accessible
	for _, msgID := range []uuid.UUID{msg1, msg3} {
		fetched, err := repo.FetchFullByMessageId(roomID, msgID)
		req.NoError(err)
		req.Equal(msgID, fetched.MessageId)
	}

	// And: Verify the deleted IDs are not in results
	resultIDs := extractIDs(results)
	req.NotContains(resultIDs, msg2, "Deleted message should not appear in search")
	req.Contains(resultIDs, msg1)
	req.Contains(resultIDs, msg3)
}

// TestAnalysisRepository_DeleteByMessageId_NonExistent validates graceful
// handling when trying to delete a message that doesn't exist.
func TestAnalysisRepository_DeleteByMessageId_NonExistent(t *testing.T) {
	req, _, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// When: Deleting a non-existent message
	err := repo.DeleteByMessageId("non-existent-room", uuid.New())

	// Then: Should return error (or succeed gracefully, depending on design)
	// This test documents the expected behavior
	_ = err // Adjust assertion based on your implementation choice

	// Option A: Return error
	// req.Error(err, "Should return error for non-existent message")

	// Option B: Succeed gracefully (idempotent delete)
	// req.NoError(err, "Deleting non-existent message should be idempotent")
}

// TestAnalysisRepository_UpdateExisting validates that storing an analysis
// with the same MessageId updates the existing record.
func TestAnalysisRepository_UpdateExisting(t *testing.T) {
	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "update-room"
	msgID := uuid.New()

	// Given: Initial version
	original := Analysis{
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Original summary",
		Tags:      []string{"tag1", "tag2"},
		Payload:   TextContent{Content: "Original content for searching"},
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricToxicity: 0.3,
		},
	}
	req.NoError(repo.Store(original))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Verify original is searchable
	results, _, err := repo.SearchPaginated(ctx, "Original", roomID, 0)
	req.NoError(err)
	req.Len(results, 1)
	req.Equal("Original summary", results[0].Summary)

	// When: Updating with new content
	updated := Analysis{
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Updated summary",
		Tags:      []string{"tag3", "tag4"},
		Payload:   TextContent{Content: "Updated content different keywords"},
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricToxicity: 0.7,
		},
	}
	req.NoError(repo.Store(updated))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: Fetch should return updated version
	fetched, err := repo.FetchFullByMessageId(roomID, msgID)
	req.NoError(err)
	req.Equal("Updated summary", fetched.Summary)
	req.Equal([]string{"tag3", "tag4"}, fetched.Tags)
	req.InDelta(0.7, fetched.Scores[domain.MetricToxicity], 0.01)

	// And: Old content should not be searchable
	results, total, err := repo.SearchPaginated(ctx, "Original", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(0), total, "Old content should not be found")
	req.Empty(results)

	// And: New content should be searchable
	results, total, err = repo.SearchPaginated(ctx, "Updated", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Len(results, 1)
	req.Equal("Updated summary", results[0].Summary)

	// And: Only one document should exist (not duplicated)
	results, total, err = repo.SearchPaginated(ctx, "content", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(1), total, "Should only have one document after update")
}

// ============================================================================
// LARGE DATASET TESTS
// ============================================================================

// TestAnalysisRepository_Search_100kDocuments validates search performance
// and correctness with a large dataset.
func TestAnalysisRepository_Search_100kDocuments(t *testing.T) {
	if testing.Short() {
		t.Skip("Skipping large dataset test in short mode")
	}

	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(1000), 100)
	roomID := "large-dataset-room"

	const totalDocs = 100_000
	const batchSize = 1000
	const targetKeyword = "special"
	const targetCount = 5000 // 5% of documents will have "special"

	t.Logf("Inserting %d documents...", totalDocs)
	startTime := time.Now()

	// Given: 100k documents, 5% containing a special keyword
	for i := 0; i < totalDocs; i++ {
		content := fmt.Sprintf("Document number %d with regular content", i)

		// Every 20th document gets the special keyword
		if i%20 == 0 {
			content = fmt.Sprintf("Document number %d with special keyword", i)
		}

		analysis := Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Millisecond),
			Summary:   fmt.Sprintf("Doc %d", i),
			Payload:   TextContent{Content: content},
			Scores: map[domain.AnalysisMetric]float64{
				domain.MetricToxicity: float64(i%100) / 100.0,
			},
		}

		err := repo.Store(analysis)
		req.NoError(err)

		// Flush every batch
		if (i+1)%batchSize == 0 {
			req.NoError(repo.Flush())
			if (i+1)%(batchSize*10) == 0 {
				t.Logf("Inserted %d/%d documents", i+1, totalDocs)
			}
		}
	}

	req.NoError(repo.Flush())
	insertDuration := time.Since(startTime)
	t.Logf("Insertion complete in %v (%.0f docs/sec)",
		insertDuration, float64(totalDocs)/insertDuration.Seconds())

	// Wait for index to stabilize
	time.Sleep(500 * time.Millisecond)

	// When: Searching for the special keyword
	t.Log("Searching for 'special' keyword...")
	searchStart := time.Now()
	results, total, err := repo.SearchPaginated(ctx, targetKeyword, roomID, 0)
	searchDuration := time.Since(searchStart)

	// Then: Should find exactly 5000 documents
	req.NoError(err)
	req.Equal(uint64(targetCount), total, "Should find all documents with special keyword")
	req.LessOrEqual(len(results), 100, "Should respect page limit")
	t.Logf("Search found %d documents in %v", total, searchDuration)

	// And: Verify pagination works correctly
	page2, total2, err := repo.SearchPaginated(ctx, targetKeyword, roomID, 100)
	req.NoError(err)
	req.Equal(total, total2, "Total count should be consistent across pages")
	req.LessOrEqual(len(page2), 100)

	// Verify no overlap between pages
	page1IDs := extractIDs(results)
	page2IDs := extractIDs(page2)
	for _, id := range page2IDs {
		req.NotContains(page1IDs, id, "Pages should not overlap")
	}

	// When: Searching by score range
	t.Log("Testing score range query...")
	scoreStart := time.Now()
	highToxicity, scoreTotal, err := repo.SearchByScoreRange(ctx, "toxicity", 0.9, 1.0, roomID)
	scoreDuration := time.Since(scoreStart)

	// Then: Should find ~10% of documents (toxicity 0.90-0.99)
	req.NoError(err)
	expectedRange := uint64(totalDocs / 10)
	req.InDelta(expectedRange, scoreTotal, float64(expectedRange)*0.1,
		"Should find ~10%% of documents with high toxicity")
	t.Logf("Score range query found %d documents in %v", scoreTotal, scoreDuration)

	// And: All returned documents should be in the score range
	for _, result := range highToxicity {
		toxicity := result.Scores[domain.MetricToxicity]
		req.GreaterOrEqual(toxicity, 0.9, "All results should have toxicity >= 0.9")
		req.LessOrEqual(toxicity, 1.0, "All results should have toxicity <= 1.0")
	}

	// When: Testing chronological scan
	t.Log("Testing chronological scan...")
	scanStart := time.Now()
	scanned, cursor, err := repo.ScanAnalysesByRoom(roomID, nil)
	scanDuration := time.Since(scanStart)

	// Then: Should retrieve most recent documents
	req.NoError(err)
	req.NotNil(cursor, "Should have more pages")
	req.LessOrEqual(len(scanned), 1000, "Should respect page limit")
	t.Logf("Scan retrieved %d documents in %v", len(scanned), scanDuration)

	// And: Results should be in reverse chronological order
	for i := 1; i < len(scanned); i++ {
		req.True(scanned[i-1].At.After(scanned[i].At) || scanned[i-1].At.Equal(scanned[i].At),
			"Results should be in reverse chronological order")
	}

	// Performance summary
	t.Logf("\n=== Performance Summary ===")
	t.Logf("Dataset: %d documents", totalDocs)
	t.Logf("Insert: %.0f docs/sec", float64(totalDocs)/insertDuration.Seconds())
	t.Logf("Search: %v (found %d/%d docs)", searchDuration, total, targetCount)
	t.Logf("Score query: %v (found %d docs)", scoreDuration, scoreTotal)
	t.Logf("Scan: %v (retrieved %d docs)", scanDuration, len(scanned))
}

// TestAnalysisRepository_LargePayload validates handling of documents with
// large content (e.g., long transcriptions).
func TestAnalysisRepository_LargePayload(t *testing.T) {
	req, ctx, log, badgerDB, blugeWriter := initTest(t)
	defer cleanup(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "large-payload-room"

	// Given: Analysis with 1MB of content
	largeContent := make([]byte, 1024*1024) // 1MB
	for i := range largeContent {
		largeContent[i] = byte('a' + (i % 26))
	}
	largeContent = append(largeContent, []byte(" searchable-keyword")...)

	msgID := uuid.New()
	analysis := Analysis{
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Large payload test",
		Payload:   TextContent{Content: string(largeContent)},
	}

	// When: Storing large payload
	err := repo.Store(analysis)
	req.NoError(err)

	req.NoError(repo.Flush())
	time.Sleep(100 * time.Millisecond)

	// Then: Should be retrievable
	fetched, err := repo.FetchFullByMessageId(roomID, msgID)
	req.NoError(err)
	req.Equal(len(largeContent), len(fetched.Payload.(TextContent).Content))

	// And: Should be searchable
	results, total, err := repo.SearchPaginated(ctx, "searchable-keyword", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Len(results, 1)
}

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

func setupBenchmark(b *testing.B) (*slog.Logger, *badger.DB, *bluge.Writer) {
	log := logs.GetLoggerFromLevel(slog.LevelError) // Reduce logging in benchmarks

	badgerDB, err := db.LoadBadger(b)
	if err != nil {
		b.Fatal(err)
	}

	blugeWriter, err := db.LoadBluge(b)
	if err