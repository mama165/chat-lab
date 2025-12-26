// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
)

type EventSink interface {
	Consume(event event.DomainEvent)
}

type Engine struct {
	rooms map[domain.RoomID]*domain.Room
	sinks []EventSink
}

func NewEngine() *Engine {
	return &Engine{
		rooms: make(map[domain.RoomID]*domain.Room),
		sinks: nil,
	}
}

func (e *Engine) RegisterRoom(room *domain.Room) {
	e.rooms[room.ID] = room
}

func (e *Engine) RegisterSink(sink EventSink) {
	e.sinks = append(e.sinks, sink)
}

func (e *Engine) Dispatch(roomID domain.RoomID, fn func(*domain.Room)) {
	room, ok := e.rooms[roomID]
	if !ok {
		return
	}

	fn(room)

	for _, evt := range room.FlushEvents() {
		for _, sink := range e.sinks {
			sink.Consume(evt)
		}
	}
}
