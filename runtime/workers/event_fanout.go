package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"fmt"
	"log/slog"
	"time"
)

// EventFanoutWorker broadcasts domain moderationChan to multiple in-process consumers.
//
// It provides best-effort fan-out with no guarantees regarding delivery,
// ordering, durability, or retries. EventFanoutWorker is not a message broker.
//
// It is intended for observability and side effects (UI, logs, metrics),
// not for core domain logic.
//
// EventFanoutWorker is safe for concurrent use by multiple goroutines.
type EventFanoutWorker struct {
	log            *slog.Logger
	permanentSinks []contract.EventSink
	registry       contract.IRegistry
	event          chan event.Event
	telemetryEvent chan event.Event
	sinkTimeout    time.Duration
}

func NewEventFanoutWorker(log *slog.Logger,
	permanentSinks []contract.EventSink,
	registry contract.IRegistry,
	domainEvent, telemetryEvent chan event.Event,
	sinkTimeout time.Duration) *EventFanoutWorker {
	return &EventFanoutWorker{
		log: log, permanentSinks: permanentSinks, registry: registry,
		event: domainEvent, telemetryEvent: telemetryEvent,
		sinkTimeout: sinkTimeout}
}

// Run starts the event distribution loop.
// It dispatches DomainEvents to rooms/sinks and forwards all moderationChan
// to the telemetry channel for observability.
// The range loop ensures all buffered moderationChan are processed before shutdown.
func (w *EventFanoutWorker) Run(ctx context.Context) error {
	for evt := range w.event {
		switch e := evt.Payload.(type) {
		case event.DomainEvent:
			w.Fanout(e)
		}
		select {
		case <-ctx.Done():
			w.log.Debug("Context done, skipping telemetry")
		case w.telemetryEvent <- evt:
		default:
			w.log.Debug("Observability telemetry event lost")
		}
	}
	w.log.Info("EventFanoutWorker: domainEvent channel drained and closed. Shutting down.")
	return nil
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
			w.log.Debug(fmt.Sprintf("Consumed event : %v", evt))
			if err := s.Consume(ctx, evt); err != nil {
				w.log.Error("sink failed", "error", err)
			}
		}()
	}
}
