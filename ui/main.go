// Package ui is a placeholder for the user interface.
// It observes events from the system and displays timelines and participant status.
// It never modifies domain state or runtime behavior.
package main

import (
	"chat-lab/projection"
	"chat-lab/runtime"
	"time"
)

func main() {
	timelines := map[string]*projection.Timeline{
		"Alice": {Owner: "Alice"},
		"Bob":   {Owner: "Bob"},
		"Clara": {Owner: "Clara"},
	}

	delays := map[string]time.Duration{
		"Alice": 0,
		"Bob":   2 * time.Millisecond,
		"Clara": 2 * time.Second,
	}

	runtime.SendMessageToAll(
		"Alice",
		"Hello everyone!",
		timelines,
		delays,
	)

	time.Sleep(3 * time.Second)

	println("\n📊 Final timelines:")
	for name, tl := range timelines {
		println("—", name)
		for _, msg := range tl.Messages {
			println("  ", msg.SenderID, "->", msg.Content)
		}
	}
}
