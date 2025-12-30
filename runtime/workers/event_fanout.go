package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

// EventFanout broadcasts domain events to multiple in-process consumers.
//
// It provides best-effort fan-out with no guarantees regarding delivery,
// ordering, durability, or retries. EventFanout is not a message broker.
//
// It is intended for observability and side effects (UI, logs, metrics),
// not for core domain logic.
//
// EventFanout is safe for concurrent use by multiple goroutines.
type EventFanout struct {
	Log            *slog.Logger
	Name           contract.WorkerName
	DomainEvent    chan event.DomainEvent
	TelemetryEvent chan event.DomainEvent
	sinks          []contract.EventSink
}

func NewEventFanout(log *slog.Logger, domainEvent, telemetryEvent chan event.DomainEvent) *EventFanout {
	return &EventFanout{Log: log, DomainEvent: domainEvent, TelemetryEvent: telemetryEvent}
}

func (w EventFanout) Add(sinks []contract.EventSink) EventFanout {
	w.sinks = append(w.sinks, sinks...)
	return w
}

func (w EventFanout) WithName(name string) contract.Worker {
	w.Name = contract.WorkerName(name)
	return w
}

func (w EventFanout) GetName() contract.WorkerName { return w.Name }

func (w EventFanout) Run(ctx context.Context) error {
	for {
		select {
		case evt := <-w.DomainEvent:
			w.Fanout(evt)
			select {
			case w.TelemetryEvent <- evt:
			default:
				w.Log.Debug("Observability telemetry event lost")
			}
		case <-ctx.Done():
			w.Log.Debug("Context done, stopping domainEvent send")
			return nil
		}
	}
}

// Fanout One sink for each event
func (w EventFanout) Fanout(event event.DomainEvent) {
	for _, sink := range w.sinks {
		sink.Consume(event)
	}
}
