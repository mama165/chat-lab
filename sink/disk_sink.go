package sink

import (
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"context"
	"fmt"
	"log/slog"
)

type DiskSink struct {
	repository storage.IMessageRepository
	log        *slog.Logger
}

func NewDiskSink(repository storage.IMessageRepository, log *slog.Logger) DiskSink {
	return DiskSink{repository: repository, log: log}
}

func (d DiskSink) Consume(_ context.Context, e event.DomainEvent) error {
	switch evt := e.(type) {
	case event.SanitizedMessage:
		return d.repository.StoreMessage(toDiskMessage(evt))
	default:
		d.log.Debug(fmt.Sprintf("Not implemented event : %v", evt))
		return nil
	}
}

func toDiskMessage(event event.SanitizedMessage) storage.DiskMessage {
	return storage.DiskMessage{
		ID:            event.ID,
		Room:          event.Room,
		Author:        event.Author,
		Content:       event.Content,
		At:            event.At,
		ToxicityScore: event.ToxicityScore,
	}
}
