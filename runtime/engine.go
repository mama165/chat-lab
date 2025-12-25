// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/domain"
	"chat-lab/projection"
	"time"
)

// SendMessage simulates producing a message and sending it to the timeline
func SendMessage(sender, content string, timeline *projection.Timeline) {
	msg := domain.Message{
		ID:        "msg1",
		SenderID:  sender,
		Content:   content,
		CreatedAt: time.Now(),
	}

	// Directly push to the projection (simulating propagation)
	timeline.Add(msg)
}

// SendMessageToAll simulates async propagation to multiple timelines
func SendMessageToAll(
	sender string,
	content string,
	timelines map[string]*projection.Timeline,
	delays map[string]time.Duration,
) {
	msg := domain.Message{
		ID:        time.Now().String(),
		SenderID:  sender,
		Content:   content,
		CreatedAt: time.Now(),
	}

	for name, tl := range timelines {
		delay := delays[name]

		go func(timelineName string, timeline *projection.Timeline, d time.Duration) {
			time.Sleep(d)
			timeline.Add(msg)
		}(name, tl, delay)
	}
}
