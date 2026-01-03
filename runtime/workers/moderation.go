package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/moderation"
	"context"
	"log/slog"
)

type ModerationWorker struct {
	name      contract.WorkerName
	moderator moderation.Moderator
	rawEvents chan event.DomainEvent
	events    chan event.DomainEvent
	log       *slog.Logger
}

func NewModerationWorker(moderator moderation.Moderator, rawEvents, events chan event.DomainEvent, log *slog.Logger) *ModerationWorker {
	return &ModerationWorker{moderator: moderator, rawEvents: rawEvents, events: events, log: log}
}

func (w ModerationWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker")
			return ctx.Err()
		case e, ok := <-w.rawEvents:
			if !ok {
				w.log.Debug("Canal is closed")
				return nil
			}
			switch evt := e.(type) {
			case event.MessagePosted:
				select {
				case <-ctx.Done():
					w.log.Debug("Stopping worker")
					return ctx.Err()
				case w.events <- event.SanitizedMessage{
					Room:    evt.Room,
					Author:  evt.Author,
					Content: w.moderator.Censor(evt.Content),
					At:      evt.At,
				}:
				}
			}
		}
	}
}
