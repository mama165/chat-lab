package event

import (
	"chat-lab/errors"
	"log/slog"
	"sync"
)

type CensoredHandler struct {
	mu      sync.Mutex
	log     *slog.Logger
	counter *Counter
}

func NewCensoredHandler(log *slog.Logger, counter *Counter) *CensoredHandler {
	return &CensoredHandler{
		log:     log,
		counter: counter,
	}
}

func (h *CensoredHandler) Handle(event Event) {
	switch event.Type {
	case CensorshipHitType:
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

		h.counter.Increment(CensorshipHitType)
		for _, word := range payload.CensoredWords {
			h.counter.IncrementHits(word)
		}
	}
}
