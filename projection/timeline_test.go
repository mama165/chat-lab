package projection

import (
	"chat-lab/domain/event"
	"context"
	"github.com/stretchr/testify/require"
	"testing"
	"time"
)

func TestTimeline_Consume_MessagePosted(t *testing.T) {
	timeline := NewTimeline()
	ctx := context.Background()

	evt1 := event.MessagePosted{
		Author:  "Alice",
		Content: "Hello Bob",
		At:      time.Now(),
	}

	evt2 := event.MessagePosted{
		Author:  "Clara",
		Content: "Hi Bob",
		At:      time.Now().Add(time.Second),
	}

	err := timeline.Consume(ctx, evt1)
	require.NoError(t, err)
	err = timeline.Consume(ctx, evt2)
	require.NoError(t, err)

	require.Len(t, timeline.Messages, 2)
	require.Equal(t, "Alice", timeline.Messages[0].SenderID)
	require.Equal(t, "Clara", timeline.Messages[1].SenderID)
}
