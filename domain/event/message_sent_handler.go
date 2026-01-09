package event

import (
	"chat-lab/errors"
	"log/slog"
	"sync"
)

// MessageSentHandler handles events when a message is sent.
// It is triggered each time a robot sends a message to another robot.
// Useful for updating observability metrics, logging, or telemetry.
type MessageSentHandler struct {
	log     *slog.Logger
	mu      sync.Mutex
	counter *Counter
}

func NewMessageSentHandler(log *slog.Logger, counter *Counter) *MessageSentHandler {
	return &MessageSentHandler{log: log, counter: counter}
}

func (p *MessageSentHandler) Handle(event Event) {
	p.mu.Lock()
	defer p.mu.Unlock()

	switch event.Type {
	case MessageSentType:
		if _, ok := event.Payload.(MessageSent); !ok {
			p.log.Error(errors.ErrInvalidPayload.Error())
		}
		p.counter.Increment(MessageSentType)
	}
}
