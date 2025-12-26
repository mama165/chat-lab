package domain

import (
	"chat-lab/domain/event"
	"github.com/stretchr/testify/require"
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
	require.Len(t, room.messages, 1)
	require.Equal(t, msg, room.messages[0])

	// Check that the outbox contains a MessagePosted event
	events := room.FlushEvents()
	require.Len(t, events, 1)

	evt, ok := events[0].(event.MessagePosted)
	require.True(t, ok)
	require.Equal(t, msg.SenderID, evt.Author)
	require.Equal(t, msg.Content, evt.Content)
	require.Equal(t, msg.CreatedAt, evt.At)

	// The outbox should be empty after FlushEvents
	require.Len(t, room.FlushEvents(), 0)
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
