package sink

import (
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"context"
)

// Timeline holds a simple local timeline
type Timeline struct {
	Owner    string
	Messages []chat.Message
}

func NewTimeline() *Timeline {
	return &Timeline{
		Messages: nil,
	}
}

func (t *Timeline) Consume(_ context.Context, e event.DomainEvent) error {
	switch evt := e.(type) {
	case event.MessagePosted:
		t.Messages = append(t.Messages, fromEvent(evt))
		return nil
	}
	return nil
}

func fromEvent(event event.MessagePosted) chat.Message {
	return chat.Message{
		SenderID:  event.Author,
		Content:   event.Content,
		CreatedAt: event.At,
	}
}
