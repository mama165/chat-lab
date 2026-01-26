package event

import (
	"chat-lab/errors"
	"fmt"
	"log/slog"
)

// ChannelCapacityHandler handles events reporting the capacity of channels.
// It is triggered to monitor the length and max capacity of internal channels.
// Useful for observability, detecting backpressure, and avoiding message drops.
type ChannelCapacityHandler struct {
	log                  *slog.Logger
	lowCapacityThreshold int
}

func NewChannelCapacityHandler(log *slog.Logger, lowCapacityThreshold int) *ChannelCapacityHandler {
	return &ChannelCapacityHandler{log: log, lowCapacityThreshold: lowCapacityThreshold}
}

func (h ChannelCapacityHandler) Handle(event Event) {
	switch event.Type {
	case ChannelCapacityType:
		payload, ok := event.Payload.(ChannelCapacity)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}
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
