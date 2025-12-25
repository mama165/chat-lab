package projection

import (
	"chat-lab/domain"
	"github.com/stretchr/testify/assert"
	"testing"
	"time"
)

func TestTimeline_PullEvents_UpdatesMessages(t *testing.T) {
	room := domain.Room{}
	timeline := Timeline{Owner: "Bob"}

	msg1 := domain.Message{
		SenderID:  "Alice",
		Content:   "Hello Bob",
		CreatedAt: time.Now(),
	}
	msg2 := domain.Message{
		SenderID:  "Clara",
		Content:   "Hi Bob",
		CreatedAt: time.Now().Add(time.Second),
	}

	room.PostMessage(msg1)
	room.PostMessage(msg2)

	events := room.PullEvents()

	// Timeline consumes events from the room
	timeline.Apply(events)

	// Check that the timeline contains the two messages
	assert.Len(t, timeline.Messages, 2)
	assert.Equal(t, msg1.SenderID, timeline.Messages[0].SenderID)
	assert.Equal(t, msg2.SenderID, timeline.Messages[1].SenderID)

	// PullEvents should empty the room outbox
	events = room.PullEvents()
	assert.Len(t, events, 0)
}
