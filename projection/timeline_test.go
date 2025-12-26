package projection

import (
	"chat-lab/domain"
	"github.com/stretchr/testify/require"
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

	events := room.FlushEvents()

	// Timeline consumes events from the room
	timeline.Apply(events)

	// Check that the timeline contains the two messages
	require.Len(t, timeline.Messages, 2)
	require.Equal(t, msg1.SenderID, timeline.Messages[0].SenderID)
	require.Equal(t, msg2.SenderID, timeline.Messages[1].SenderID)

	// FlushEvents should empty the room outbox
	events = room.FlushEvents()
	require.Len(t, events, 0)
}
