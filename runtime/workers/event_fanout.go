package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

// EventFanoutWorker dispatches events from the event channel to their sinks.
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
	analysisSink  contract.EventSink[contract.FileAnalyzerEvent]
	eventChan     chan event.Event
	telemetryChan chan event.Event
}

func NewEventFanoutWorker(
	log *slog.Logger,
	analysisSink contract.EventSink[contract.FileAnalyzerEvent],
	eventChan, telemetryChan chan event.Event) *EventFanoutWorker {
	return &EventFanoutWorker{
		log:           log,
		analysisSink:  analysisSink,
		eventChan:     eventChan,
		telemetryChan: telemetryChan,
	}
}

// Run starts the eventChan distribution loop.
// It dispatches file analysis events to the analysis sink and forwards every
// event to the telemetry channel for observability.
// It stops when the context is canceled.
func (w *EventFanoutWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case evt, ok := <-w.eventChan:
			if !ok {
				return nil
			}
			if e, ok := evt.Payload.(contract.FileAnalyzerEvent); ok {
				w.handleFileEvent(ctx, e)
			}
			select {
			case w.telemetryChan <- evt:
			default:
				w.log.Debug("Observability telemetry eventChan lost")
			}
		}
	}
}

func (w *EventFanoutWorker) handleFileEvent(ctx context.Context, evt contract.FileAnalyzerEvent) {
	if err := w.analysisSink.Consume(ctx, evt); err != nil {
		w.log.Error("sink failed", "error", err)
	}
}
