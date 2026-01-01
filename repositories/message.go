//go:generate go run go.uber.org/mock/mockgen -source=message.go -destination=../mocks/mock_repository.go -package=mocks
package repositories

import (
	pb "chat-lab/proto"
	"fmt"
	"github.com/dgraph-io/badger/v4"
	"github.com/samber/lo"
	"google.golang.org/protobuf/proto"
	"log/slog"
	"time"
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
	Room    int
	Author  string
	Content string
	At      time.Time
}

// StoreMessage Key is --> msg:{room_id}:{timestamp}:{user_id}
func (m MessageRepository) StoreMessage(message DiskMessage) error {
	key := fmt.Sprintf("msg:%d:%d:%s", message.Room, message.At.UnixNano(), message.Author)
	bytes, err := proto.Marshal(lo.ToPtr(fromDiskMessage(message)))
	if err != nil {
		return err
	}
	return m.Db.Update(func(txn *badger.Txn) error {
		err = txn.Set([]byte(key), bytes)
		return err
	})
}

// GetMessages Key is --> msg:
func (m MessageRepository) GetMessages(room int) ([]DiskMessage, error) {
	var byteMessages [][]byte
	var diskMessages []DiskMessage
	err := m.Db.View(func(txn *badger.Txn) error {
		it := txn.NewIterator(badger.DefaultIteratorOptions)
		defer it.Close()
		prefix := []byte(fmt.Sprintf("msg:%d:", room))
		for it.Seek(prefix); it.ValidForPrefix(prefix); it.Next() {
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
		diskMessages = append(diskMessages, toDiskMessage(&messagePb))
	}
	return diskMessages, err
}

func fromDiskMessage(message DiskMessage) pb.Message {
	return pb.Message{
		Room:    int64(message.Room),
		Author:  message.Author,
		Content: message.Content,
		At:      message.At.UnixNano(),
	}
}

func toDiskMessage(messagePb *pb.Message) DiskMessage {
	return DiskMessage{
		Room:    int(messagePb.Room),
		Author:  messagePb.Author,
		Content: messagePb.Content,
		At:      time.Unix(0, messagePb.At).UTC(),
	}
}
