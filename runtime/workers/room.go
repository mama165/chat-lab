package workers

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

type RoomWorker struct {
	room     *domain.Room
	commands chan domain.Command
	events   chan event.DomainEvent
	log      *slog.Logger
}

func NewRoomWorker(room *domain.Room, commands chan domain.Command, events chan event.DomainEvent, log *slog.Logger) RoomWorker {
	return RoomWorker{room: room, commands: commands, events: events, log: log}
}

func (w RoomWorker) GetName() WorkerName {
	//TODO implement me
	panic("implement me")
}

func (w RoomWorker) WithName(name string) Worker {
	//TODO implement me
	panic("implement me")
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

// Petite fonction helper pour la clarté
func toEvent(c domain.PostMessageCommand) event.MessagePosted {
	return event.MessagePosted{
		Author:  c.SenderID,
		Content: c.Content,
		At:      c.CreatedAt,
	}
}
