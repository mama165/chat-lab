package event

import (
	"chat-lab/errors"
	"fmt"
	"log/slog"
	"strings"
)

type ProcessTrackerHandler struct {
	log *slog.Logger
}

func NewProcessTrackerHandler(log *slog.Logger) *ProcessTrackerHandler {
	return &ProcessTrackerHandler{log: log}
}

func (h ProcessTrackerHandler) Handle(event Event) {
	switch event.Type {
	case PIDTrackerType:
		payload, ok := event.Payload.(ProcessTracker)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}
		h.log.Debug(fmt.Sprintf(" [SPECIALIST][%s] | PID %d | STATUS %s | CPU %.2f%% | RAM %.2f%%",
			strings.ToUpper(string(payload.Metric)), payload.PID, payload.Status, payload.Cpu, payload.Ram))
	}
}
