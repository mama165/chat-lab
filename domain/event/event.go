package event

import (
	"chat-lab/domain"
	"time"

	"github.com/google/uuid"
)

type Type string

const (
	DomainType          Type = "DOMAIN_TYPE"
	CensorshipHit       Type = "CENSORSHIP_HIT"
	RestartedAfterPanic Type = "WORKER_RESTARTED_AFTER_PANIC"
	ChannelCapacityType Type = "CHANNEL_CAPACITY"
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
	ID            uuid.UUID
	Room          int
	Author        string
	Content       string
	CensoredWords []string
	At            time.Time
}

func (m MessagePosted) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}
func (m SanitizedMessage) RoomID() domain.RoomID {
	return domain.RoomID(m.Room)
}

// Telemetry

type WorkerRestartedAfterPanic struct {
	WorkerName string
}

type ChannelCapacity struct {
	ChannelName string
	Capacity    int
	Length      int
}
