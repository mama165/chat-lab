package domain

import (
	"chat-lab/domain/event"
	"github.com/stretchr/testify/assert"
	"testing"
	"time"
)

func TestRoom_PostMessage_AddsMessageAndEvent(t *testing.T) {
	room := Room{}

	msg := Message{
		SenderID:  "Alice",
		Content:   "Hello Bob",
		CreatedAt: time.Now(),
	}

	room.PostMessage(msg)

	// Check that the message is added to Room
	assert.Len(t, room.messages, 1)
	assert.Equal(t, msg, room.messages[0])

	// Check that the outbox contains a MessagePosted event
	events := room.PullEvents()
	assert.Len(t, events, 1)

	evt, ok := events[0].(event.MessagePosted)
	assert.True(t, ok)
	assert.Equal(t, msg.SenderID, evt.Author)
	assert.Equal(t, msg.Content, evt.Content)
	assert.Equal(t, msg.CreatedAt, evt.At)

	// The outbox should be empty after PullEvents
	assert.Len(t, room.PullEvents(), 0)
}

// Temporary helper to append a dummy event for testing
func (r *Room) AppendEventForTest(e event.DomainEvent) {
	r.outbox = append(r.outbox, e)
}

type DummyEvent struct{}

func (DummyEvent) Name() string {
	return "Dummy"
}

func (DummyEvent) OccurredAt() time.Time {
	return time.Now()
}
