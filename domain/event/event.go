package event

import (
	"chat-lab/domain"
	"time"
)

type DomainEvent interface {
	RoomID() domain.RoomID
}

type MessagePosted struct {
	Room    int
	Author  string
	Content string
	At      time.Time
}

func (m MessagePosted) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}

type SanitizedMessage struct {
	Room    int
	Author  string
	Content string
	At      time.Time
}

func (m SanitizedMessage) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}
