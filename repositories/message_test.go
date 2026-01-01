package repositories

import (
	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func Test_Record_Multiple_Message(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
	req.NoError(err)
	defer db.Close()

	repository := MessageRepository{Db: db}
	room := 1
	content := "this message will self destruct in 5 seconds"
	at := time.Now().UTC()
	diskMessages := []DiskMessage{
		{room, "Alice", content, at},
		{room, "Bob", content, at.Add(1 * time.Minute)},
		{room, "Clara", content, at.Add(2 * time.Minute)},
	}
	for _, dm := range diskMessages {
		err = repository.StoreMessage(dm)
		req.NoError(err)
	}
	fetchedMessages, err := repository.GetMessages(room)
	req.NoError(err)
	req.Len(fetchedMessages, len(diskMessages))
	req.Equal(diskMessages, fetchedMessages)
}

func Test_Record_Multiple_Message_And_Limit(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()).WithLoggingLevel(badger.ERROR))
	req.NoError(err)
	defer db.Close()

	limit := 2
	repository := MessageRepository{Db: db, limitMessage: &limit}
	room := 1
	content := "this message will self destruct in 5 seconds"
	at := time.Now().UTC()
	diskMessages := []DiskMessage{
		{room, "Alice", content, at},
		{room, "Bob", content, at.Add(1 * time.Minute)},
		{room, "Clara", content, at.Add(2 * time.Minute)},
	}
	for _, dm := range diskMessages {
		err = repository.StoreMessage(dm)
		req.NoError(err)
	}
	fetchedMessages, err := repository.GetMessages(room)
	req.NoError(err)
	req.Len(fetchedMessages, limit)
}
