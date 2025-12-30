package contract

import (
	"chat-lab/domain/event"
	"context"
)

type EventSink interface {
	Consume(e event.DomainEvent)
}

type WorkerName string

// Worker doesn't protect itself
// Can be silly, focused
type Worker interface {
	WithName(name string) Worker
	GetName() WorkerName
	Run(ctx context.Context) error
}
