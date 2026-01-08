package event

import (
	"chat-lab/domain"
	"github.com/google/uuid"
	"time"
)

type Type string

const (
	DomainType        Type = "DOMAIN_TYPE"
	MessagePostedType Type = "MESSAGE_POSTED"
	BufferUsageType   Type = "BUFFER_USAGE"
	CensorshipHit     Type = "CENSORSHIP_HIT"
)

type Event struct {
	Type      Type
	CreatedAt time.Time
	Payload   any
}

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

type SanitizedMessage struct {
	ID      uuid.UUID
	Room    int
	Author  string
	Content string
	At      time.Time
}

func (m MessagePosted) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}

func (m SanitizedMessage) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}
