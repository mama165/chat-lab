package workers

import (
	"chat-lab/domain/event"
	"context"
	"log/slog"
	"time"
)

type TelemetryWorker struct {
	log            *slog.Logger
	metricInterval time.Duration
	telemetryChan  chan event.Event
	handlers       []event.Handler
}

func NewTelemetryWorker(log *slog.Logger,
	metricInterval time.Duration,
	telemetryChan chan event.Event,
	handlers []event.Handler) *TelemetryWorker {
	return &TelemetryWorker{
		log:            log,
		metricInterval: metricInterval,
		telemetryChan:  telemetryChan,
		handlers:       handlers,
	}
}

func (w TelemetryWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(500 * time.Millisecond)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
		case <-ticker.C:
			select {
			case <-ctx.Done():
			case evt := <-w.telemetryChan:
				w.handle(evt)
			}
		}
	}
}

func (w TelemetryWorker) handle(event event.Event) {
	for _, h := range w.handlers {
		h.Handle(event)
	}
}
