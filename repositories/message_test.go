package repositories

import (
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
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
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
	fetchedMessages, err := repository.GetMessages(room)
	req.NoError(err)

	// Then the messages are sorted
	req.Len(fetchedMessages, len(sortedDiskMessages))
	req.Equal(sortedDiskMessages, fetchedMessages)
}

func Test_Record_Multiple_Message_And_Limit(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
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
	fetchedMessages, err := repository.GetMessages(room)
	req.NoError(err)
	req.Len(fetchedMessages, limit)
}
