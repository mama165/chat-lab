package event

import (
	"chat-lab/errors"
	"chat-lab/observability"
	"fmt"
	"log/slog"
)

// ChannelCapacityHandler handles events reporting the capacity of channels.
// It is triggered to monitor the length and max capacity of internal channels,
// and logs a warning when a channel holds items and has at most lowCapacityThreshold free slots.
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
		// An empty queue is never "nearly full", even when the threshold is larger
		// than the queue itself.
		capacityLeft := payload.Capacity - payload.Length
		if payload.Length > 0 && capacityLeft <= h.lowCapacityThreshold {
			h.log.Warn(fmt.Sprintf("channel %s is nearly full : %d / %d, capacity left : %d",
				payload.ChannelName, payload.Length, payload.Capacity, capacityLeft))
		}
	}
}
