package event

import (
	"chat-lab/domain"
	"github.com/google/uuid"
	"time"
)

type DomainEvent interface {
	RoomID() domain.RoomID
}

type MessagePosted struct {
	ID      uuid.UUID
	Room    int
	Author  string
	Content string
	At      time.Time
}

func (m MessagePosted) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}

type SanitizedMessage struct {
	ID      uuid.UUID
	Room    int
	Author  string
	Content string
	At      time.Time
}

func (m SanitizedMessage) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}
