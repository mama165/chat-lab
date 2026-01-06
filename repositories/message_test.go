package repositories

import (
	"fmt"
	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"log/slog"
	"sort"
	"testing"
	"time"
)

func Test_Record_And_Get_Sorted_Messages(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)
	defer db.Close()

	repository := NewMessageRepository(db, slog.Default(), nil)
	room := 1
	content := "this message will self destruct in 5 seconds"
	at := time.Now().UTC()
	diskMessages := []DiskMessage{
		{uuid.New(), room, "Alice", content, at},
		{uuid.New(), room, "Bob", content, at.Add(1 * time.Minute)},
		{uuid.New(), room, "Clara", content, at.Add(2 * time.Minute)},
	}

	sortedDiskMessages := make([]DiskMessage, len(diskMessages))
	copy(sortedDiskMessages, diskMessages)
	sort.Slice(sortedDiskMessages, func(i, j int) bool {
		return sortedDiskMessages[i].At.After(sortedDiskMessages[j].At)
	})
	for _, dm := range diskMessages {
		err = repository.StoreMessage(dm)
		req.NoError(err)
	}

	// When fetching messages
	fetchedMessages, _, err := repository.GetMessages(room, "")
	req.NoError(err)

	// Then the messages are sorted
	req.Len(fetchedMessages, len(sortedDiskMessages))
	req.Equal(sortedDiskMessages, fetchedMessages)
}

func Test_Record_Multiple_Message_And_Limit(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)
	defer db.Close()

	limit := 2
	repository := NewMessageRepository(db, slog.Default(), &limit)
	room := 1
	content := "this message will self destruct in 5 seconds"
	at := time.Now().UTC()
	diskMessages := []DiskMessage{
		{uuid.New(), room, "Alice", content, at},
		{uuid.New(), room, "Bob", content, at.Add(1 * time.Minute)},
		{uuid.New(), room, "Clara", content, at.Add(2 * time.Minute)},
	}
	for _, dm := range diskMessages {
		err = repository.StoreMessage(dm)
		req.NoError(err)
	}
	fetchedMessages, _, err := repository.GetMessages(room, "")
	req.NoError(err)
	req.Len(fetchedMessages, limit)
}

func Test_MessageRepository_Pagination(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)
	defer db.Close()

	limit := 4
	repo := NewMessageRepository(db, slog.Default(), &limit)
	room := 42
	now := time.Now().UTC()

	// 1. Insertion de 10 messages (du plus vieux au plus récent)
	for i := 1; i <= 10; i++ {
		err = repo.StoreMessage(DiskMessage{
			ID:      uuid.New(),
			Room:    room,
			Author:  fmt.Sprintf("user_%d", i),
			Content: fmt.Sprintf("Message %d", i),
			At:      now.Add(time.Duration(i) * time.Minute),
		})
		req.NoError(err)
	}

	// --- PAGE 1 ---
	msgs1, cursor1, err := repo.GetMessages(room, "")
	req.NoError(err)
	req.Len(msgs1, 4)
	req.Equal("user_10", msgs1[0].Author) // Le plus récent
	req.Equal("user_7", msgs1[3].Author)
	req.NotEmpty(cursor1)

	// --- PAGE 2 ---
	msgs2, cursor2, err := repo.GetMessages(room, cursor1)
	req.NoError(err)
	req.Len(msgs2, 4)
	// Vérification de l'absence de doublon : le premier de la p2 doit être le message 6
	req.Equal("user_6", msgs2[0].Author)
	req.Equal("user_3", msgs2[3].Author)
	req.NotEmpty(cursor2)

	// --- PAGE 3 (Fin) ---
	msgs3, cursor3, err := repo.GetMessages(room, cursor2)
	req.NoError(err)
	req.Len(msgs3, 2) // Il ne reste que 2 messages (2 et 1)
	req.Equal("user_2", msgs3[0].Author)
	req.Equal("user_1", msgs3[1].Author)

	// On vérifie que si on continue, on n'a plus rien
	msgs4, _, err := repo.GetMessages(room, cursor3)
	req.NoError(err)
	req.Empty(msgs4)
}
