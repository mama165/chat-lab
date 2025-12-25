// Package domain contains core concepts of the chat system.
// This file defines Message events and related rules.
// Messages are immutable and validated by the domain.
package domain

import "time"

// Message represents an immutable chat event.
type Message struct {
	ID        string // unique identifier
	SenderID  string
	Content   string
	CreatedAt time.Time
}
