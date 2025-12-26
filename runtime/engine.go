// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
)

type Engine struct {
	rooms map[domain.RoomID]*domain.Room
	sink  []EventSink
}

type EventSink interface {
	Consume(event event.DomainEvent)
}
