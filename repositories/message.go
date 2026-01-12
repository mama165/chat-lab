//go:generate go run go.uber.org/mock/mockgen -source=message.go -destination=../mocks/mock_message_repository.go -package=mocks
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

type IMessageRepository interface {
	StoreMessage(message DiskMessage) error
	GetMessages(room int, cursor *string) ([]DiskMessage, *string, error)
}

type MessageRepository struct {
	db            *badger.DB
	log           *slog.Logger
	limitMessages *int
}

func NewMessageRepository(db *badger.DB, log *slog.Logger, limitMessages *int) MessageRepository {
	return MessageRepository{db: db, log: log, limitMessages: limitMessages}
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
	return m.db.Update(func(txn *badger.Txn) error {
		err = txn.Set([]byte(key), bytes)
		return err
	})
}

// GetMessages retrieves messages for a specific room using a prefix scan.
// Thanks to the padded timestamp in the key, messages are naturally sorted by time.
// It stops collecting messages once the configured limitMessages is reached.
func (m MessageRepository) GetMessages(room int, cursor *string) ([]DiskMessage, *string, error) {
	var byteMessages [][]byte
	var diskMessages []DiskMessage
	var lastKey string
	err := m.db.View(func(txn *badger.Txn) error {
		prefixStr := fmt.Sprintf("msg:%d:", room)
		prefix := []byte(prefixStr)
		prefixLen := len(prefixStr)
		options := badger.DefaultIteratorOptions
		options.Reverse = true
		it := txn.NewIterator(options)
		defer it.Close()

		var seekKey []byte
		switch cursor {
		case nil:
			// Let's go the oldest position msg:22222:9999999999999999999
			// Then, we go back and find few messages
			seekKey = append(prefix, []byte("9999999999999999999")...)
		default:
			seekKey = append(prefix, []byte(*cursor)...)
		}

		it.Seek(seekKey)

		if cursor != nil && it.ValidForPrefix(prefix) {
			it.Next()
		}

		for ; it.ValidForPrefix(prefix); it.Next() {
			if m.limitMessages != nil && len(byteMessages) == *m.limitMessages {
				m.log.Debug(fmt.Sprintf("Maximum of %d message reached", *m.limitMessages))
				break
			}
			item := it.Item()
			// Memorize cursor part of the actual key
			lastKey = string(item.Key()[prefixLen:])
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
		return nil, nil, err
	}

	for _, b := range byteMessages {
		var messagePb pb.Message
		if err = proto.Unmarshal(b, &messagePb); err != nil {
			return nil, nil, err
		}
		message, err := toDiskMessage(&messagePb)
		if err != nil {
			return nil, nil, err
		}
		diskMessages = append(diskMessages, message)
	}
	return diskMessages, &lastKey, err
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
