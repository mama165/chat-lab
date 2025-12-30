package contract

import (
	"chat-lab/domain/event"
	"context"
)

type ISupervisor interface {
	Add(worker ...Worker) ISupervisor
	Run(ctx context.Context)
	Start(ctx context.Context, worker Worker)
	Stop()
}

type WorkerName string

// Worker doesn't protect itself
// Can be silly, focused
type Worker interface {
	WithName(name string) Worker
	GetName() WorkerName
	Run(ctx context.Context) error
}

type EventSink interface {
	Consume(e event.DomainEvent)
}
