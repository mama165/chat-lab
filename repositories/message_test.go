package repositories

import (
	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func TestRecordMessage(t *testing.T) {
	req := require.New(t)
	db, err := badger.Open(badger.DefaultOptions(t.TempDir()))
	req.NoError(err)
	defer db.Close()

	repository := MessageRepository{Db: db}
	room := 1
	content := "this message will self destruct in 5 seconds"
	diskMessage := DiskMessage{room, "Bob", content, time.Now().UTC()}
	err = repository.StoreMessage(diskMessage)
	req.NoError(err)
	fetchedMessages, err := repository.GetMessages(room)
	req.NoError(err)
	req.Len(fetchedMessages, 1)
	req.Equal(diskMessage, fetchedMessages[0])
}
