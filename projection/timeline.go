// Package projection builds local timelines from observed events.
// Handles ordering, deduplication, and projections.
// Does not emit events or interact with UI directly.
package projection

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"fmt"
)

// Timeline holds a simple local timeline
type Timeline struct {
	Owner    string
	Messages []domain.Message
}

func (t *Timeline) Apply(events []event.DomainEvent) {
	for _, e := range events {
		evt, ok := e.(event.MessagePosted)
		if !ok {
			continue
		}
		t.Add(fromEvent(evt))
	}
}
func (t *Timeline) Add(msg domain.Message) {
	t.Messages = append(t.Messages, msg)

	fmt.Printf(
		"🕒 [%s] received message from %s: %s\n",
		t.Owner,
		msg.SenderID,
		msg.Content,
	)
}

func fromEvent(event event.MessagePosted) domain.Message {
	return domain.Message{
		SenderID:  event.Author,
		Content:   event.Content,
		CreatedAt: event.At,
	}
}
