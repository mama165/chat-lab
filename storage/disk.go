package storage

import (
	"chat-lab/domain/event"
	"chat-lab/repositories"
	"log/slog"
)

type DiskSink struct {
	repository repositories.Repository
	log        *slog.Logger
}

func (d DiskSink) Consume(e event.DomainEvent) {
	switch evt := e.(type) {
	case event.MessagePosted:
		if err := d.repository.StoreMessage(toDiskMessage(evt)); err != nil {
			d.log.Error(err.Error())
		}
	}
}

func toDiskMessage(event event.MessagePosted) repositories.DiskMessage {
	return repositories.DiskMessage{
		Room:    event.Room,
		Author:  event.Author,
		Content: event.Content,
		At:      event.At,
	}
}
