package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

type RoomWorker struct {
	name     contract.WorkerName
	room     *domain.Room
	commands chan domain.Command
	events   chan event.DomainEvent
	log      *slog.Logger
}

func NewRoomWorker(room *domain.Room, commands chan domain.Command, events chan event.DomainEvent, log *slog.Logger) RoomWorker {
	return RoomWorker{room: room, commands: commands, events: events, log: log}
}

func (w RoomWorker) GetName() contract.WorkerName {
	return w.name
}

func (w RoomWorker) WithName(name string) contract.Worker {
	w.name = contract.WorkerName(name)
	return w
}

func (w RoomWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker")
			return ctx.Err()
		case cmd, ok := <-w.commands:
			if !ok {
				return nil
			}
			if postCmd, ok := cmd.(domain.PostMessageCommand); ok {
				w.room.PostMessage(domain.Message{
					SenderID:  postCmd.SenderID,
					Content:   postCmd.Content,
					CreatedAt: postCmd.CreatedAt,
				})
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
		Author:  c.SenderID,
		Content: c.Content,
		At:      c.CreatedAt,
	}
}
