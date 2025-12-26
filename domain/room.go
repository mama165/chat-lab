package domain

import (
	"chat-lab/domain/event"
)

type RoomID int

type Room struct {
	roomID   RoomID
	messages []Message
	outbox   []event.DomainEvent
}

func NewRoom(id int) *Room {
	return &Room{
		roomID:   RoomID(id),
		messages: nil,
		outbox:   nil,
	}
}

func (r *Room) PostMessage(message Message) {
	r.messages = append(r.messages, message)
	r.outbox = append(r.outbox, toEvent(message))
}

func toEvent(message Message) event.DomainEvent {
	return event.MessagePosted{
		Author:  message.SenderID,
		Content: message.Content,
		At:      message.CreatedAt,
	}
}

func (r *Room) FlushEvents() []event.DomainEvent {
	events := r.outbox
	r.outbox = nil
	return events
}
