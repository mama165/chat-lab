package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

// EventFanoutWorker broadcasts domain events to multiple in-process consumers.
//
// It provides best-effort fan-out with no guarantees regarding delivery,
// ordering, durability, or retries. EventFanoutWorker is not a message broker.
//
// It is intended for observability and side effects (UI, logs, metrics),
// not for core domain logic.
//
// EventFanoutWorker is safe for concurrent use by multiple goroutines.
type EventFanoutWorker struct {
	Log            *slog.Logger
	DomainEvent    chan event.DomainEvent
	TelemetryEvent chan event.DomainEvent
	sinks          []contract.EventSink
}

func NewEventFanout(log *slog.Logger, domainEvent, telemetryEvent chan event.DomainEvent) *EventFanoutWorker {
	return &EventFanoutWorker{Log: log, DomainEvent: domainEvent, TelemetryEvent: telemetryEvent}
}

func (w *EventFanoutWorker) Add(sinks []contract.EventSink) contract.Worker {
	w.sinks = append(w.sinks, sinks...)
	return w
}

func (w *EventFanoutWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.Log.Debug("Context done, stopping domainEvent send")
			return nil
		case evt, ok := <-w.DomainEvent:
			if !ok {
				w.Log.Debug("Canal is closed")
				return nil
			}
			w.Fanout(evt)
			select {
			case <-ctx.Done():
				w.Log.Debug("Context done, stopping domainEvent send")
				return nil
			case w.TelemetryEvent <- evt:
			default:
				w.Log.Debug("Observability telemetry event lost")
			}
		}
	}
}

// Fanout One sink for each domain event
// 1. Disk storage
// 2. Websocket
func (w *EventFanoutWorker) Fanout(event event.DomainEvent) {
	for _, sink := range w.sinks {
		sink.Consume(event)
	}
}
