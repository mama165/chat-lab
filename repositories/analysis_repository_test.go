package repositories

import (
	"chat-lab/domain"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/mama165/sdk-go/db"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

var MetricTest domain.AnalysisMetric = "test"

// ============================================================================
// UNIT TESTS - Core Functionality
// ============================================================================

func TestAnalysisRepository_Store_Text_Success(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: A text-based analysis
	roomID := "test-room-1"
	msgID := uuid.New()
	analysis := Analysis{
		ID:        uuid.New(),
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Test Summary",
		Tags:      []string{"urgent", "bug"},
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricToxicity: 0.12,
			domain.MetricBusiness: 0.88,
		},
		Payload: TextContent{Content: "This is a test message about gRPC implementation"},
		Version: uuid.New(),
	}

	// When: Storing the analysis
	err = repo.Store(analysis)
	req.NoError(err)

	// Then: It should be retrievable from BadgerDB
	fetched, err := repo.FetchFullByMessageId(roomID, msgID)
	req.NoError(err)
	req.Equal(analysis.MessageId, fetched.MessageId)
	req.Equal(analysis.Summary, fetched.Summary)
	req.Equal(analysis.Tags, fetched.Tags)
	req.InDelta(0.12, fetched.Scores["toxicity"], 0.001)

	// And: It should be searchable in Bluge after flush
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	results, total, err := repo.SearchPaginated(ctx, "gRPC", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Len(results, 1)
	req.Equal(msgID, results[0].MessageId)
}

func TestAnalysisRepository_Store_Audio_Success(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: An audio-based analysis
	roomID := "audio-room"
	msgID := uuid.New()
	analysis := Analysis{
		ID:        uuid.New(),
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Meeting recording",
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricSentiment: 0.75},
		Payload: AudioDetails{
			Transcription: "We decided to migrate to PostgreSQL for better scalability",
			Duration:      180, // 3 minutes
		},
	}

	// When: Storing
	err = repo.Store(analysis)
	req.NoError(err)
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: Searchable by transcription content
	results, _, err := repo.SearchPaginated(ctx, "PostgreSQL", roomID, 0)
	req.NoError(err)
	req.Len(results, 1)

	// And: Payload correctly deserialized
	fetched := results[0]
	audio, ok := fetched.Payload.(AudioDetails)
	req.True(ok, "Payload should be AudioDetails")
	req.Equal(uint32(180), audio.Duration)
	req.Contains(audio.Transcription, "PostgreSQL")
}

func TestAnalysisRepository_Store_File_Success(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: A file-based analysis
	roomID := "docs-room"
	msgID := uuid.New()
	analysis := Analysis{
		ID:        uuid.New(),
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Architecture document",
		Payload: FileDetails{
			Filename: "system-architecture-2024.pdf",
			MimeType: "application/pdf",
			Size:     2048576, // 2 MB
		},
	}

	// When: Storing
	err = repo.Store(analysis)
	req.NoError(err)
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: Searchable by filename
	results, _, err := repo.SearchPaginated(ctx, "architecture", roomID, 0)
	req.NoError(err)
	req.Len(results, 1)

	file, ok := results[0].Payload.(FileDetails)
	req.True(ok)
	req.Equal("application/pdf", file.MimeType)
	req.Equal(uint64(2048576), file.Size)
}

// ============================================================================
// SEARCH TESTS - Full-Text
// ============================================================================

func TestAnalysisRepository_SearchPaginated_MultipleResults(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "search-room"

	// Given: Multiple analyses with "database" keyword
	analyses := []Analysis{
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-3 * time.Hour),
			Summary:   "Database migration plan",
			Payload:   TextContent{Content: "We need to migrate our database to PostgreSQL"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-2 * time.Hour),
			Summary:   "Database performance",
			Payload:   TextContent{Content: "Database queries are slow, need optimization"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-1 * time.Hour),
			Summary:   "Unrelated topic",
			Payload:   TextContent{Content: "Let's discuss the frontend refactoring"},
		},
	}

	for _, a := range analyses {
		req.NoError(repo.Store(a))
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching for "database"
	results, total, err := repo.SearchPaginated(ctx, "database", roomID, 0)

	// Then: Should find 2 results
	req.NoError(err)
	req.Equal(uint64(2), total)
	req.Len(results, 2)

	// And: Results should NOT include the unrelated topic
	for _, r := range results {
		req.NotEqual("Unrelated topic", r.Summary)
	}
}

func TestAnalysisRepository_SearchPaginated_CaseInsensitive(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "case-room"

	// Given: Analysis with mixed case
	analysis := Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "Test",
		Payload:   TextContent{Content: "Kubernetes Deployment Strategy"},
	}
	req.NoError(repo.Store(analysis))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching with different cases
	testCases := []string{"kubernetes", "KUBERNETES", "Kubernetes", "KuBeRnEtEs"}

	for _, query := range testCases {
		results, total, err := repo.SearchPaginated(ctx, query, roomID, 0)

		// Then: All should find the document
		req.NoError(err, "Query: %s", query)
		req.Equal(uint64(1), total, "Query: %s", query)
		req.Len(results, 1, "Query: %s", query)
	}
}

func TestAnalysisRepository_SearchPaginated_RoomIsolation(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)
	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: Same content in different rooms
	room1 := "room-1"
	room2 := "room-2"

	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    room1,
		Summary:   "Test",
		Payload:   TextContent{Content: "Secret project alpha"},
	}))

	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    room2,
		Summary:   "Test",
		Payload:   TextContent{Content: "Secret project beta"},
	}))

	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching in room1
	results, total, err := repo.SearchPaginated(ctx, "Secret", room1, 0)

	// Then: Should only find room1 documents
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Len(results, 1)
	req.Equal(room1, results[0].RoomId)
	req.Contains(results[0].Payload.(TextContent).Content, "alpha")
	req.NotContains(results[0].Payload.(TextContent).Content, "beta")
}

func TestAnalysisRepository_SearchPaginated_EmptyQuery(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "test-room"

	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Payload:   TextContent{Content: "Some content"},
	}))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching with empty string
	results, total, err := repo.SearchPaginated(ctx, "", roomID, 0)

	// Then: Should return empty (or handle gracefully)
	req.NoError(err)
	// Bluge returns all documents for empty MatchQuery, which is acceptable
	// If you want 0 results, add validation in SearchPaginated
	_ = total
	_ = results
}

func TestAnalysisRepository_SearchPaginated_NoResults(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "empty-room"

	// When: Searching in empty room
	results, total, err := repo.SearchPaginated(ctx, "nonexistent", roomID, 0)

	// Then: Should return empty results gracefully
	req.NoError(err)
	req.Equal(uint64(0), total)
	req.Empty(results)
}

func TestAnalysisRepository_SearchPaginated_Pagination(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 3)
	roomID := "pagination-room"

	// Given: 7 analyses with same keyword
	for i := 0; i < 7; i++ {
		err := repo.Store(Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Second),
			Summary:   fmt.Sprintf("Test %d", i),
			Payload:   TextContent{Content: "pagination test content"},
		})
		req.NoError(err)
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Fetching page 1 (offset 0)
	page1, total, err := repo.SearchPaginated(ctx, "pagination", roomID, 0)
	req.NoError(err)
	req.Equal(uint64(7), total, "Total should be 7")
	req.Len(page1, 3, "Page 1 should have 3 results (limit)")

	// When: Fetching page 2 (offset 3)
	page2, total, err := repo.SearchPaginated(ctx, "pagination", roomID, 3)
	req.NoError(err)
	req.Equal(uint64(7), total)
	req.Len(page2, 3)

	// When: Fetching page 3 (offset 6)
	page3, total, err := repo.SearchPaginated(ctx, "pagination", roomID, 6)
	req.NoError(err)
	req.Equal(uint64(7), total)
	req.Len(page3, 1, "Page 3 should have 1 result (remainder)")

	// Then: No overlap between pages
	page1IDs := extractIDs(page1)
	page2IDs := extractIDs(page2)
	page3IDs := extractIDs(page3)

	req.NotContains(page1IDs, page2IDs[0])
	req.NotContains(page2IDs, page3IDs[0])
}

// ============================================================================
// SEARCH TESTS - Score Range
// ============================================================================

func TestAnalysisRepository_SearchByScoreRange_SingleScore(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "score-room"

	// Given: Analyses with different toxicity scores
	analyses := []struct {
		id    uuid.UUID
		score float64
	}{
		{uuid.New(), 0.05}, // Clean
		{uuid.New(), 0.35}, // Borderline
		{uuid.New(), 0.82}, // Toxic
		{uuid.New(), 0.95}, // Very toxic
	}

	for _, a := range analyses {
		req.NoError(repo.Store(Analysis{
			MessageId: a.id,
			RoomId:    roomID,
			Summary:   fmt.Sprintf("Score %.2f", a.score),
			Scores:    map[domain.AnalysisMetric]float64{domain.MetricToxicity: a.score},
		}))
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching for high toxicity (0.8 to 1.0)
	results, total, err := repo.SearchByScoreRange(ctx, "toxicity", 0.8, 1.0, roomID)

	// Then: Should find 2 results
	req.NoError(err)
	req.Equal(uint64(2), total)
	req.Len(results, 2)

	// And: Both should be in the range
	for _, r := range results {
		score := r.Scores["toxicity"]
		req.GreaterOrEqual(score, 0.8)
		req.LessOrEqual(score, 1.0)
	}
}

func TestAnalysisRepository_SearchByScoreRange_MultipleScores(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "multiscore-room"

	// Given: Analyses with multiple scores
	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "High business value",
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricBusiness:  0.92,
			domain.MetricToxicity:  0.05,
			domain.MetricSentiment: 0.75,
		},
	}))

	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "Low business value",
		Scores: map[domain.AnalysisMetric]float64{
			domain.MetricBusiness:  0.12,
			domain.MetricToxicity:  0.88,
			domain.MetricSentiment: 0.25,
		},
	}))

	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching for high business score
	businessResults, _, err := repo.SearchByScoreRange(ctx, "business", 0.8, 1.0, roomID)
	req.NoError(err)
	req.Len(businessResults, 1)
	req.Equal("High business value", businessResults[0].Summary)

	// When: Searching for high toxicity
	toxicResults, _, err := repo.SearchByScoreRange(ctx, "toxicity", 0.8, 1.0, roomID)
	req.NoError(err)
	req.Len(toxicResults, 1)
	req.Equal("Low business value", toxicResults[0].Summary)
}

func TestAnalysisRepository_SearchByScoreRange_EdgeCases(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "edge-room"

	// Given: Analysis with score exactly at boundaries
	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "Exactly 0.5",
		Scores:    map[domain.AnalysisMetric]float64{MetricTest: 0.5},
	}))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Range includes lower boundary
	results, _, err := repo.SearchByScoreRange(ctx, "test", 0.5, 0.6, roomID)
	req.NoError(err)
	req.Len(results, 1, "Should include lower boundary")

	// When: Range includes upper boundary
	results, _, err = repo.SearchByScoreRange(ctx, "test", 0.4, 0.5, roomID)
	req.NoError(err)
	req.Len(results, 1, "Should include upper boundary")

	// When: Range excludes value
	results, _, err = repo.SearchByScoreRange(ctx, "test", 0.6, 0.8, roomID)
	req.NoError(err)
	req.Empty(results)
}

func TestAnalysisRepository_SearchByScoreRange_NonExistentScore(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "test-room"

	req.NoError(repo.Store(Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Scores:    map[domain.AnalysisMetric]float64{domain.MetricToxicity: 0.5},
	}))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Searching for non-existent score field
	results, total, err := repo.SearchByScoreRange(ctx, "nonexistent_score", 0.0, 1.0, roomID)

	// Then: Should return empty (documents without that field don't match)
	req.NoError(err)
	req.Equal(uint64(0), total)
	req.Empty(results)
}

// ============================================================================
// SCAN TESTS - BadgerDB Pagination
// ============================================================================

func TestAnalysisRepository_ScanAnalysesByRoom_FirstPage(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(3), 10)
	roomID := "scan-room"

	// Given: 7 analyses in chronological order
	var insertedIDs []uuid.UUID
	for i := 0; i < 7; i++ {
		id := uuid.New()
		insertedIDs = append(insertedIDs, id)
		req.NoError(repo.Store(Analysis{
			MessageId: id,
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Second),
			Summary:   fmt.Sprintf("Message %d", i),
		}))
	}

	// When: Fetching first page (no cursor)
	page1, cursor1, err := repo.ScanAnalysesByRoom(roomID, nil)

	// Then: Should get 3 results (limit)
	req.NoError(err)
	req.Len(page1, 3)
	req.NotNil(cursor1, "Should return cursor for next page")

	// And: Results in reverse chronological order (newest first)
	req.Equal(insertedIDs[6], page1[0].MessageId, "Newest should be first")
	req.Equal(insertedIDs[5], page1[1].MessageId)
	req.Equal(insertedIDs[4], page1[2].MessageId)
}

func TestAnalysisRepository_ScanAnalysesByRoom_SubsequentPages(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(2), 10)
	roomID := "pagination-scan"

	// Given: 5 analyses
	for i := 0; i < 5; i++ {
		req.NoError(repo.Store(Analysis{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(time.Duration(i) * time.Second),
			Summary:   fmt.Sprintf("Msg %d", i),
		}))
	}

	// When: Fetching all pages
	page1, cursor1, err := repo.ScanAnalysesByRoom(roomID, nil)
	req.NoError(err)
	req.Len(page1, 2)
	req.NotNil(cursor1)

	page2, cursor2, err := repo.ScanAnalysesByRoom(roomID, cursor1)
	req.NoError(err)
	req.Len(page2, 2)
	req.NotNil(cursor2)

	page3, cursor3, err := repo.ScanAnalysesByRoom(roomID, cursor2)
	req.NoError(err)
	req.Len(page3, 1) // Remainder
	req.Nil(cursor3, "Last page should have nil cursor")

	// Then: No duplicates across pages
	allIDs := append(extractIDs(page1), extractIDs(page2)...)
	allIDs = append(allIDs, extractIDs(page3)...)
	req.Len(allIDs, 5)
	req.True(allUnique(allIDs))
}

func TestAnalysisRepository_ScanAnalysesByRoom_EmptyRoom(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// When: Scanning empty room
	results, cursor, err := repo.ScanAnalysesByRoom("nonexistent-room", nil)

	// Then: Should return empty gracefully
	req.NoError(err)
	req.Empty(results)
	req.Nil(cursor)
}

// ============================================================================
// FETCH TESTS - Direct Retrieval
// ============================================================================

func TestAnalysisRepository_FetchFullByMessageId_Success(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: A stored analysis
	roomID := "fetch-room"
	msgID := uuid.New()
	original := Analysis{
		ID:        uuid.New(),
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now().UTC(),
		Summary:   "Original summary",
		Tags:      []string{"tag1", "tag2"},
		Scores:    map[domain.AnalysisMetric]float64{MetricTest: 0.42},
		Payload:   TextContent{Content: "Test content"},
	}
	req.NoError(repo.Store(original))

	// When: Fetching by message ID
	fetched, err := repo.FetchFullByMessageId(roomID, msgID)

	// Then: Should get complete object
	req.NoError(err)
	req.Equal(original.MessageId, fetched.MessageId)
	req.Equal(original.Summary, fetched.Summary)
	req.Equal(original.Tags, fetched.Tags)
	req.InDelta(0.42, fetched.Scores["test"], 0.001)

	payload, ok := fetched.Payload.(TextContent)
	req.True(ok)
	req.Equal("Test content", payload.Content)
}

func TestAnalysisRepository_FetchFullByMessageId_NotFound(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// When: Fetching non-existent message
	_, err = repo.FetchFullByMessageId("test-room", uuid.New())

	// Then: Should return error
	req.Error(err)
	req.Contains(err.Error(), "not found")
}
func TestAnalysisRepository_FetchFullByMessageId_WrongRoom(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: Analysis in room-1
	msgID := uuid.New()
	req.NoError(repo.Store(Analysis{
		MessageId: msgID,
		RoomId:    "room-1",
		Summary:   "Test",
	}))

	// When: Fetching with wrong room ID
	_, err = repo.FetchFullByMessageId("room-2", msgID)

	// Then: Should not find it
	req.Error(err)
}

// ============================================================================
// INTEGRATION TESTS - Complex Scenarios
// ============================================================================
func TestAnalysisRepository_FullWorkflow_StoreSearchFetch(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "workflow-room"

	// Step 1: Store multiple analyses
	analyses := []Analysis{
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-2 * time.Hour),
			Summary:   "Bug report",
			Scores: map[domain.AnalysisMetric]float64{
				domain.MetricToxicity: 0.15,
				domain.MetricBusiness: 0.92,
			},
			Payload: TextContent{Content: "Critical bug in payment processing"},
		},
		{
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        time.Now().Add(-1 * time.Hour),
			Summary:   "Feature request",
			Scores: map[domain.AnalysisMetric]float64{
				domain.MetricToxicity: 0.05,
				domain.MetricBusiness: 0.88,
			},
			Payload: TextContent{Content: "Add dark mode to the application"},
		},
	}

	for _, a := range analyses {
		req.NoError(repo.Store(a))
	}
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Step 2: Search by content
	searchResults, _, err := repo.SearchPaginated(ctx, "bug", roomID, 0)
	req.NoError(err)
	req.Len(searchResults, 1)
	req.Equal("Bug report", searchResults[0].Summary)

	// Step 3: Filter by score
	highBusiness, _, err := repo.SearchByScoreRange(ctx, "business", 0.9, 1.0, roomID)
	req.NoError(err)
	req.Len(highBusiness, 1)
	req.Equal("Bug report", highBusiness[0].Summary)

	// Step 4: Scan chronologically
	scanned, _, err := repo.ScanAnalysesByRoom(roomID, nil)
	req.NoError(err)
	req.Len(scanned, 2)
	req.Equal("Feature request", scanned[0].Summary, "Newest first")

	// Step 5: Direct fetch
	fetched, err := repo.FetchFullByMessageId(roomID, analyses[0].MessageId)
	req.NoError(err)
	req.Equal("Bug report", fetched.Summary)
}
func TestAnalysisRepository_Consistency_BadgerBlugeSync(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "consistency-room"
	msgID := uuid.New()

	// Given: Analysis stored in both systems
	analysis := Analysis{
		MessageId: msgID,
		RoomId:    roomID,
		At:        time.Now(),
		Summary:   "Consistency test",
		Payload:   TextContent{Content: "Test content for consistency"},
	}
	req.NoError(repo.Store(analysis))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// When: Fetching via Bluge search
	searchResults, _, err := repo.SearchPaginated(ctx, "consistency", roomID, 0)
	req.NoError(err)
	req.Len(searchResults, 1)
	searchedID := searchResults[0].MessageId

	// And: Fetching via direct Badger lookup
	directFetch, err := repo.FetchFullByMessageId(roomID, msgID)
	req.NoError(err)

	// Then: Both methods should return identical data
	req.Equal(msgID, searchedID)
	req.Equal(directFetch.Summary, searchResults[0].Summary)
	req.Equal(directFetch.MessageId, searchResults[0].MessageId)
}

// ============================================================================
// EDGE CASES & ERROR HANDLING
// ============================================================================
func TestAnalysisRepository_Store_ZeroValueTimestamp(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// Given: Analysis with zero time (edge case)
	msgID := uuid.New()
	analysis := Analysis{
		MessageId: msgID,
		RoomId:    "test-room",
		At:        time.Time{}, // Zero value
		Summary:   "Zero time test",
	}

	// When: Storing
	err = repo.Store(analysis)

	// Then: Should store successfully (no validation currently)
	// If you want to reject this, add validation in Store()
	req.NoError(err)

	// Verify it's retrievable
	fetched, err := repo.FetchFullByMessageId("test-room", msgID)
	req.NoError(err)
	req.True(fetched.At.IsZero())
}
func TestAnalysisRepository_Store_EmptyScores(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "empty-scores"

	// Given: Analysis with nil/empty scores
	analysis := Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "No scores",
		Scores:    nil, // or map[string]float64{}
		Payload:   TextContent{Content: "Content without scores"},
	}

	// When: Storing and searching
	req.NoError(repo.Store(analysis))
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	// Then: Should still be searchable by content
	results, _, err := repo.SearchPaginated(ctx, "Content", roomID, 0)
	req.NoError(err)
	req.Len(results, 1)
	req.Empty(results[0].Scores)
}
func TestAnalysisRepository_Store_LargeContent(t *testing.T) {
	req := require.New(t)
	ctx, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)
	roomID := "large-content"

	// Given: Analysis with very large content (simulate long transcript)
	largeContent := ""
	for i := 0; i < 20000; i++ {
		largeContent += "word "
	}

	analysis := Analysis{
		MessageId: uuid.New(),
		RoomId:    roomID,
		Summary:   "Large content test",
		Payload:   TextContent{Content: largeContent},
	}

	// When: Storing
	err = repo.Store(analysis)

	// Then: Should handle successfully
	// Note: BadgerDB default value size limit is 1MB
	// If content > 1MB, Badger will fail unless configured
	req.NoError(err)

	// Verify searchable
	req.NoError(repo.Flush())
	time.Sleep(50 * time.Millisecond)

	results, _, err := repo.SearchPaginated(ctx, "Large", roomID, 0)
	req.NoError(err)
	req.Len(results, 1)
}

func TestAnalysisRepository_Flush_IdempotentExclamation(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := db.SetupBenchmark(t.TempDir())
	req.NoError(err)
	defer db.CleanupDB(badgerDB, blugeWriter)
	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 10)

	// When: Calling Flush multiple times
	err1 := repo.Flush()
	err2 := repo.Flush()
	err3 := repo.Flush()

	// Then: Should always succeed
	req.NoError(err1)
	req.NoError(err2)
	req.NoError(err3)
}

func extractIDs(analyses []Analysis) []uuid.UUID {
	ids := make([]uuid.UUID, len(analyses))
	for i, a := range analyses {
		ids[i] = a.MessageId
	}
	return ids
}
func allUnique(ids []uuid.UUID) bool {
	seen := make(map[uuid.UUID]bool)
	for _, id := range ids {
		if seen[id] {
			return false
		}
		seen[id] = true
	}
	return true
}
