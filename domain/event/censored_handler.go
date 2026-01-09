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
	h.mu.Lock()
	defer h.mu.Unlock()

	switch event.Type {
	case CensorshipHit:
		payload, ok := event.Payload.(Censored)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}
		h.counter++
		h.hit[payload.Word]++
	}
}
