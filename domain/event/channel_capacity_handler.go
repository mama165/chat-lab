package event

import (
	"chat-lab/errors"
	"chat-lab/observability"
	"fmt"
	"log/slog"
)

// ChannelCapacityHandler handles events reporting the capacity of channels.
// It is triggered to monitor the length and max capacity of internal channels.
// Useful for observability, detecting backpressure, and avoiding message drops.
type ChannelCapacityHandler struct {
	log                  *slog.Logger
	lowCapacityThreshold int
	monitoring           *observability.MonitoringManager
}

func NewChannelCapacityHandler(log *slog.Logger,
	lowCapacityThreshold int,
	monitoring *observability.MonitoringManager,
) *ChannelCapacityHandler {
	return &ChannelCapacityHandler{
		log:                  log,
		lowCapacityThreshold: lowCapacityThreshold,
		monitoring:           monitoring,
	}
}

func (h ChannelCapacityHandler) Handle(event Event) {
	switch event.Type {
	case ChannelCapacityType:
		payload, ok := event.Payload.(ChannelCapacity)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}

		stats := observability.MonitoringStats{
			MaxCapacity:      uint32(payload.Capacity),
			CurrentQueueSize: payload.Length,
		}
		h.monitoring.MergeExternalStats(stats)

		h.log.Debug(fmt.Sprintf("Channel %s usage: %d / %d", payload.ChannelName, payload.Length, payload.Capacity))
		if payload.Capacity <= 0 {
			// In case of unbuffered channel
			return
		}
		capacityLeft := payload.Capacity - payload.Length
		if capacityLeft > 0 && capacityLeft <= h.lowCapacityThreshold {
			h.log.Warn(fmt.Sprintf("metric event channel capacity left : %d", capacityLeft))
		}
	}
}
