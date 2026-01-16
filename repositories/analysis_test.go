package repositories

import (
	"fmt"
	"github.com/blugelabs/bluge"
	"log/slog"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

func TestAnalysisRepository_Store_And_Get_Types(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).
		WithLoggingLevel(badger.ERROR))
	req.NoError(err)
	defer db.Close()

	blugeCfg := bluge.DefaultConfig(t.TempDir())
	blugeWriter, err := bluge.OpenWriter(blugeCfg)
	req.NoError(err)
	defer blugeWriter.Close()

	repo := NewAnalysisRepository(db, blugeWriter, slog.Default(), lo.ToPtr(50))
	now := time.Now().UTC()
	roomID := "room-123"

	tests := []struct {
		name    string
		payload any
	}{
		{
			name:    "Simple Text Content",
			payload: TextContent{Content: "This is a clean text analysis"}},
		{
			name: "Audio Transcription",
			payload: AudioDetails{
				Transcription: "Hello world from gRPC specialist",
				Duration:      120,
			},
		},
		{
			name: "Technical PDF File",
			payload: FileDetails{
				Filename: "server_logs.pdf",
				MimeType: "application/pdf",
				Size:     1024 * 50,
			},
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			messageID := uuid.New()

			original := Analysis{
				Id:        uuid.New(),
				MessageId: messageID,
				RoomId:    roomID,
				At:        now,
				Summary:   "Summary for " + tt.name,
				Tags:      []string{"test", "auto-generated"},
				Scores:    map[string]float64{"toxicity": 0.01, "business": 0.95},
				Payload:   tt.payload,
			}

			err := repo.Store(original)
			req.NoError(err)

			fetched, err := repo.GetByMessageId(roomID, messageID)
			req.NoError(err)

			req.Equal(original.Id, fetched.Id)
			req.Equal(original.MessageId, fetched.MessageId)
			req.Equal(original.Summary, fetched.Summary)
			req.Equal(original.Tags, fetched.Tags)
			req.InDelta(original.Scores["business"], fetched.Scores["business"], 0.001)
			req.Equal(original.Payload, fetched.Payload)
		})
	}
}

func TestAnalysisRepository_Pagination(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
	req.NoError(err)
	defer db.Close()

	blugeCfg := bluge.DefaultConfig(t.TempDir())
	blugeWriter, err := bluge.OpenWriter(blugeCfg)
	req.NoError(err)
	defer blugeWriter.Close()

	limit := 2
	repo := NewAnalysisRepository(db, blugeWriter, slog.Default(), &limit)
	roomID := "war-room-01"
	now := time.Now().UTC()

	for i := 1; i <= 5; i++ {
		err := repo.Store(Analysis{
			Id:        uuid.New(),
			MessageId: uuid.New(),
			RoomId:    roomID,
			At:        now.Add(time.Duration(i) * time.Minute),
			Summary:   fmt.Sprintf("Summary %d", i),
			Payload:   fmt.Sprintf("Content %d", i),
		})
		req.NoError(err)
	}

	// --- PAGE 1 ---
	list1, cursor1, err := repo.GetAnalyses(roomID, nil)
	req.NoError(err)
	req.Len(list1, 2)
	req.Equal("Summary 5", list1[0].Summary)
	req.Equal("Summary 4", list1[1].Summary)
	req.NotEmpty(cursor1)

	// --- PAGE 2 ---
	list2, cursor2, err := repo.GetAnalyses(roomID, cursor1)
	req.NoError(err)
	req.Len(list2, 2)
	req.Equal("Summary 3", list2[0].Summary)
	req.Equal("Summary 2", list2[1].Summary)
	req.NotEmpty(cursor2)

	// --- PAGE 3 ---
	list3, _, err := repo.GetAnalyses(roomID, cursor2)
	req.NoError(err)
	req.Len(list3, 1)
	req.Equal("Summary 1", list3[0].Summary)
}
