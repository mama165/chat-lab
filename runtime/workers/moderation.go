package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

type ModerationWorker struct {
	name      contract.WorkerName
	rawEvents chan event.DomainEvent
	events    chan event.DomainEvent
	log       *slog.Logger
}

func NewModerationWorker(rawEvents, events chan event.DomainEvent) contract.Worker {
	return ModerationWorker{rawEvents: rawEvents, events: events}
}

func (w ModerationWorker) GetName() contract.WorkerName {
	return w.name
}

func (w ModerationWorker) WithName(name string) contract.Worker {
	w.name = contract.WorkerName(name)
	return w
}

func (w ModerationWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker")
			return ctx.Err()
		case e := <-w.rawEvents:
			switch evt := e.(type) {
			case event.MessagePosted:
				select {
				case <-ctx.Done():
					w.log.Debug("Stopping worker")
					return ctx.Err()
				case w.events <- event.SanitizedMessage{
					Room:    evt.Room,
					Author:  evt.Author,
					Content: sanitize(evt.Content),
					At:      evt.At,
				}:
				}
			}
		}
	}
}

func sanitize(content string) string {
	return "I'm a clean content"
}
