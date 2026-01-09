package event

import (
	"log/slog"
	"time"
)

type LatencyHandler struct {
	log              *slog.Logger
	latencyThreshold time.Duration
}

func NewLatencyHandler(log *slog.Logger, latencyThreshold time.Duration) *LatencyHandler {
	return &LatencyHandler{log: log, latencyThreshold: latencyThreshold}
}

func (h *LatencyHandler) Handle(e Event) {
	if payload, ok := e.Payload.(SanitizedMessage); ok {
		leadTime := time.Since(payload.At)

		h.log.Info("telemetry: processing latency",
			"room_id", payload.Room,
			"author", payload.Author,
			"lead_time_ms", leadTime.Milliseconds(),
			"lead_time_ns", leadTime.Nanoseconds(),
		)

		if leadTime > h.latencyThreshold {
			h.log.Warn("high latency detected", "lead_time", leadTime)
		}
	}
}
