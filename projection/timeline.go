// Package projection builds local timelines from observed events.
// Handles ordering, deduplication, and projections.
// Does not emit events or interact with UI directly.
package projection

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
)

// Timeline holds a simple local timeline
type Timeline struct {
	Owner    string
	Messages []domain.Message
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

func fromEvent(event event.MessagePosted) domain.Message {
	return domain.Message{
		SenderID:  event.Author,
		Content:   event.Content,
		CreatedAt: event.At,
	}
}
