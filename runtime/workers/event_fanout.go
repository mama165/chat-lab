package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"fmt"
	"log/slog"
	"time"
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
	permanentSinks []contract.EventSink
	registry       contract.IRegistry
	TelemetryEvent chan event.DomainEvent
	DomainEvent    chan event.DomainEvent
	sinkTimeout    time.Duration
}

func NewEventFanout(log *slog.Logger,
	permanentSinks []contract.EventSink,
	registry contract.IRegistry,
	domainEvent, telemetryEvent chan event.DomainEvent, sinkTimeout time.Duration) *EventFanoutWorker {
	return &EventFanoutWorker{
		Log: log, permanentSinks: permanentSinks, registry: registry,
		DomainEvent: domainEvent, TelemetryEvent: telemetryEvent,
		sinkTimeout: sinkTimeout}
}

func (w *EventFanoutWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.Log.Debug("Context done, stopping domainEvent send")
			return nil
		case evt, ok := <-w.DomainEvent:
			if !ok {
				w.Log.Debug("channel is closed")
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

// Fanout distributes an event to all relevant sinks (permanent and room-specific).
// Each delivery is executed in its own goroutine to prevent a slow or failing sink
// from blocking the main worker loop or delaying delivery to other participants.
// It uses a derived context with a timeout to ensure that every goroutine
// eventually terminates, protecting the system against resource leaks.
func (w *EventFanoutWorker) Fanout(evt event.DomainEvent) {
	roomSinks := w.registry.GetSinksForRoom(evt.RoomID())
	allSinks := append(w.permanentSinks, roomSinks...)

	for _, sink := range allSinks {
		// Important : capture variable for the goroutine
		s := sink
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), w.sinkTimeout)
			defer cancel()
			w.Log.Debug(fmt.Sprintf("Consumed event : %v", evt))
			if err := s.Consume(ctx, evt); err != nil {
				w.Log.Error("sink failed", "error", err)
			}
		}()
	}
}
