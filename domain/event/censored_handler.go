package event

import (
	"chat-lab/errors"
	"log/slog"
	"sync"
)

type CensoredHandler struct {
	mu      sync.Mutex
	log     *slog.Logger
	counter uint64
	hit     map[string]uint64
}

func NewCensoredHandler(log *slog.Logger) *CensoredHandler {
	return &CensoredHandler{
		log:     log,
		counter: 0,
		hit:     make(map[string]uint64),
	}
}

func (h *CensoredHandler) Handle(event Event) {
	switch event.Type {
	case CensorshipHit:
		payload, ok := event.Payload.(SanitizedMessage)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}
		if len(payload.CensoredWords) == 0 {
			return
		}
		h.mu.Lock()
		defer h.mu.Unlock()

		h.counter++
		for _, word := range payload.CensoredWords {
			h.hit[word]++
		}
	}
}
