//go:generate go run go.uber.org/mock/mockgen -source=message.go -destination=../mocks/mock_repository.go -package=mocks
package repositories

import (
	pb "chat-lab/proto/storage"
	"fmt"
	"log/slog"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/samber/lo"
	"google.golang.org/protobuf/proto"
)

type Repository interface {
	StoreMessage(message DiskMessage) error
	GetMessages(room int) ([]DiskMessage, error)
}

type MessageRepository struct {
	Db            *badger.DB
	log           *slog.Logger
	LimitMessages *int
}

func NewMessageRepository(db *badger.DB, log *slog.Logger, limitMessages *int) MessageRepository {
	return MessageRepository{Db: db, log: log, LimitMessages: limitMessages}
}

type DiskMessage struct {
	ID      uuid.UUID
	Room    int
	Author  string
	Content string
	At      time.Time
}

// StoreMessage persists a message in BadgerDB.
// The key is formatted as "msg:{room_id}:{timestamp_padded}:{uuid}" to:
//  1. Ensure chronological sorting using 19-digit zero padding (lexicographical order).
//  2. Prevent data loss by using UUID as a collision disconnector if two messages
//     arrive at the same nanosecond.
func (m MessageRepository) StoreMessage(message DiskMessage) error {
	key := fmt.Sprintf("msg:%d:%019d:%s",
		message.Room,
		message.At.UnixNano(),
		message.ID,
	)
	bytes, err := proto.Marshal(lo.ToPtr(fromDiskMessage(message)))
	if err != nil {
		return err
	}
	return m.Db.Update(func(txn *badger.Txn) error {
		err = txn.Set([]byte(key), bytes)
		return err
	})
}

// GetMessages retrieves messages for a specific room using a prefix scan.
// Thanks to the padded timestamp in the key, messages are naturally sorted by time.
// It stops collecting messages once the configured LimitMessages is reached.
func (m MessageRepository) GetMessages(room int) ([]DiskMessage, error) {
	var byteMessages [][]byte
	var diskMessages []DiskMessage
	err := m.Db.View(func(txn *badger.Txn) error {
		options := badger.DefaultIteratorOptions
		options.Reverse = true
		it := txn.NewIterator(options)
		defer it.Close()

		prefix := []byte(fmt.Sprintf("msg:%d:", room))
		seekKey := append(prefix, []byte("9999999999999999999")...)

		for it.Seek(seekKey); it.ValidForPrefix(prefix); it.Next() {
			if m.LimitMessages != nil && len(byteMessages) == *m.LimitMessages {
				m.log.Debug(fmt.Sprintf("Maximum of %d message reached", *m.LimitMessages))
				break
			}
			item := it.Item()
			err := item.Value(func(value []byte) error {
				byteMessages = append(byteMessages, value)
				return nil
			})
			if err != nil {
				return err
			}
		}
		return nil
	})
	if err != nil {
		return nil, err
	}

	for _, b := range byteMessages {
		var messagePb pb.Message
		if err = proto.Unmarshal(b, &messagePb); err != nil {
			return nil, err
		}
		message, err := toDiskMessage(&messagePb)
		if err != nil {
			return nil, err
		}
		diskMessages = append(diskMessages, message)
	}
	return diskMessages, err
}

func fromDiskMessage(message DiskMessage) pb.Message {
	return pb.Message{
		Id:      message.ID.String(),
		Room:    int64(message.Room),
		Author:  message.Author,
		Content: message.Content,
		At:      message.At.UnixNano(),
	}
}

func toDiskMessage(messagePb *pb.Message) (DiskMessage, error) {
	parsedID, err := uuid.Parse(messagePb.Id)
	if err != nil {
		return DiskMessage{}, err
	}
	return DiskMessage{
		ID:      parsedID,
		Room:    int(messagePb.Room),
		Author:  messagePb.Author,
		Content: messagePb.Content,
		At:      time.Unix(0, messagePb.At).UTC(),
	}, nil
}
