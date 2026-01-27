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
	log           *slog.Logger
	diskSink      contract.EventSink[contract.DomainEvent]
	analysisSink  contract.EventSink[contract.FileAnalyzerEvent]
	registry      contract.IRegistry
	eventChan     chan event.Event
	telemetryChan chan event.Event
	sinkTimeout   time.Duration
}

func NewEventFanoutWorker(
	log *slog.Logger,
	diskSink contract.EventSink[contract.DomainEvent],
	analysisSink contract.EventSink[contract.FileAnalyzerEvent],
	registry contract.IRegistry,
	eventChan, telemetryChan chan event.Event,
	sinkTimeout time.Duration) *EventFanoutWorker {
	return &EventFanoutWorker{
		log:          log,
		diskSink:     diskSink,
		analysisSink: analysisSink,
		registry:     registry,
		eventChan:    eventChan, telemetryChan: telemetryChan,
		sinkTimeout: sinkTimeout}
}

// Run starts the eventChan distribution loop.
// It dispatches DomainEvents to rooms/sinks and forwards all moderationChan
// to the telemetry channel for observability.
// The range loop ensures all buffered moderationChan are processed before shutdown.
func (w *EventFanoutWorker) Run(ctx context.Context) error {
	for evt := range w.eventChan {
		switch e := evt.Payload.(type) {
		case contract.DomainEvent:
			w.Fanout(e)
		case contract.FileAnalyzerEvent:
			w.handleFileEvent(ctx, e)
		}
		select {
		case <-ctx.Done():
			w.log.Debug("Context done, skipping telemetry")
		case w.telemetryChan <- evt:
		default:
			w.log.Debug("Observability telemetry eventChan lost")
		}
	}
	w.log.Info("EventFanoutWorker: domainEvent channel drained and closed. Shutting down.")
	return nil
}

func (w *EventFanoutWorker) handleFileEvent(ctx context.Context, evt contract.FileAnalyzerEvent) {
	w.log.Debug(fmt.Sprintf("Consumed eventChan : %v", evt))
	if err := w.analysisSink.Consume(ctx, evt); err != nil {
		w.log.Error("sink failed", "error", err)

	}
}

// Fanout distributes an eventChan to all relevant sinks (disk and room-specific).
// Each delivery is executed in its own goroutine to prevent a slow or failing sink
// from blocking the main worker loop or delaying delivery to other participants.
// It uses a derived context with a timeout to ensure that every goroutine
// eventually terminates, protecting the system against resource leaks.
func (w *EventFanoutWorker) Fanout(evt contract.DomainEvent) {
	roomSinks := w.registry.GetSinksForRoom(evt.RoomID())
	allSinks := append(roomSinks, w.diskSink)

	for _, sink := range allSinks {
		// Important : capture variable for the goroutine
		s := sink
		go func() {
			ctx, cancel := context.WithTimeout(context.Background(), w.sinkTimeout)
			defer cancel()
			w.log.Debug(fmt.Sprintf("Consumed eventChan : %v", evt))
			if err := s.Consume(ctx, evt); err != nil {
				w.log.Error("sink failed", "error", err)
			}
		}()
	}
}
