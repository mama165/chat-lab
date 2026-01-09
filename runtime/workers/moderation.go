package workers

import (
	"chat-lab/domain/event"
	"chat-lab/moderation"
	"context"
	"log/slog"
	"time"
)

type ModerationWorker struct {
	moderator      moderation.Moderator
	moderationChan chan event.Event
	events         chan event.Event
	log            *slog.Logger
}

func NewModerationWorker(moderator moderation.Moderator,
	moderationChan, events chan event.Event, log *slog.Logger) *ModerationWorker {
	return &ModerationWorker{moderator: moderator,
		moderationChan: moderationChan, events: events, log: log,
	}
}

func (w ModerationWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker ")
			return ctx.Err()
		case e, ok := <-w.moderationChan:
			if !ok {
				w.log.Debug("Channel is closed")
				return nil
			}
			switch evt := e.Payload.(type) {
			case event.MessagePosted:
				select {
				case <-ctx.Done():
					w.log.Debug("Stopping worker")
					return ctx.Err()
				case w.events <- w.toSanitizedEvent(evt):
				}
			}
		}
	}
}

func (w ModerationWorker) toSanitizedEvent(evt event.MessagePosted) event.Event {
	sanitized, foundWords := w.moderator.Censor(evt.Content)
	return event.Event{
		Type:      event.DomainType,
		CreatedAt: time.Now().UTC(),
		Payload: event.SanitizedMessage{
			Room:          evt.Room,
			Author:        evt.Author,
			Content:       sanitized,
			CensoredWords: foundWords,
			At:            evt.At,
		}}
}
