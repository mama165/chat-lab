package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"context"
	"fmt"
	"github.com/google/uuid"
	"log/slog"
	"time"
)

// Ensure *PoolUnitWorker implements the contract.Worker interface at compile time.
// This prevents "type mismatch" errors from appearing late in other packages
// and acts as a static assertion of our architectural rules.
var _ contract.Worker = (*PoolUnitWorker)(nil)

type PoolUnitWorker struct {
	rooms          map[chat.RoomID]*chat.Room
	commands       chan chat.Command
	moderationChan chan event.Event
	log            *slog.Logger
}

func NewPoolUnitWorker(
	rooms map[chat.RoomID]*chat.Room,
	commands chan chat.Command,
	moderationChan chan event.Event,
	log *slog.Logger) *PoolUnitWorker {
	return &PoolUnitWorker{
		rooms:          rooms,
		commands:       commands,
		moderationChan: moderationChan,
		log:            log,
	}
}

func (w *PoolUnitWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker")
			return ctx.Err()
		case command, ok := <-w.commands:
			if !ok {
				w.log.Debug("Channel is closed")
				return nil
			}
			_, ok = w.rooms[command.RoomID()]
			if !ok {
				w.log.Debug(fmt.Sprintf("Room %d doesn't exist", command.RoomID()))
			}
			switch cmd := command.(type) {
			case chat.PostMessageCommand:
				select {
				case <-ctx.Done():
					return ctx.Err()
				case w.moderationChan <- toMessagePostedEvent(cmd):
				}
			}
		}
	}
}

func toMessagePostedEvent(c chat.PostMessageCommand) event.Event {
	return event.Event{
		Type:      event.DomainType,
		CreatedAt: time.Now().UTC(),
		Payload: event.MessagePosted{
			ID:      uuid.New(),
			Room:    c.Room,
			Author:  c.UserID,
			Content: c.Content,
			At:      c.CreatedAt,
		},
	}
}
