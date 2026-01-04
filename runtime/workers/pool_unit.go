package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"fmt"
	"github.com/google/uuid"
	"log/slog"
)

// Ensure *PoolUnitWorker implements the contract.Worker interface at compile time.
// This prevents "type mismatch" errors from appearing late in other packages
// and acts as a static assertion of our architectural rules.
var _ contract.Worker = (*PoolUnitWorker)(nil)

type PoolUnitWorker struct {
	rooms    map[domain.RoomID]*domain.Room
	commands chan domain.Command
	events   chan event.DomainEvent
	log      *slog.Logger
}

func NewPoolUnitWorker(
	rooms map[domain.RoomID]*domain.Room,
	commands chan domain.Command,
	events chan event.DomainEvent,
	log *slog.Logger) *PoolUnitWorker {
	return &PoolUnitWorker{
		rooms:    rooms,
		commands: commands,
		events:   events,
		log:      log,
	}
}

func (w *PoolUnitWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker")
			return ctx.Err()
		case cmd, ok := <-w.commands:
			if !ok {
				w.log.Debug("Channel is closed")
				return nil
			}
			_, ok = w.rooms[cmd.RoomID()]
			if !ok {
				w.log.Debug(fmt.Sprintf("Room %d doesn't exist", cmd.RoomID()))
			}
			if postCmd, ok := cmd.(domain.PostMessageCommand); ok {
				select {
				case <-ctx.Done():
					return ctx.Err()
				case w.events <- toEvent(postCmd):
				}
			}
		}
	}
}

func toEvent(c domain.PostMessageCommand) event.MessagePosted {
	return event.MessagePosted{
		ID:      uuid.New(),
		Room:    c.Room,
		Author:  c.UserID,
		Content: c.Content,
		At:      c.CreatedAt,
	}
}
