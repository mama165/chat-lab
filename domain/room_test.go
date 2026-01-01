package domain

import (
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func TestRoom_PostMessage(t *testing.T) {
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
}
