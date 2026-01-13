package event

import (
	"chat-lab/domain"
	"sync"
	"time"

	"github.com/google/uuid"
)

type Type string

const (
	DomainType              Type = "DOMAIN_TYPE"
	CensorshipHitType       Type = "CENSORSHIP_HIT"
	RestartedAfterPanicType Type = "WORKER_RESTARTED_AFTER_PANIC"
	ChannelCapacityType     Type = "CHANNEL_CAPACITY"
	MessageSentType         Type = "MESSAGE_SENT"
)

type Counter struct {
	mu         sync.Mutex
	eventCount map[Type]uint64
	wordHits   map[string]uint64
}

func NewCounter() *Counter {
	return &Counter{
		eventCount: make(map[Type]uint64),
		wordHits:   make(map[string]uint64),
	}
}

func (c *Counter) Increment(evt Type) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.eventCount[evt]++
}

func (c *Counter) IncrementHits(word string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.wordHits[word]++
}

func (c *Counter) Get(evt Type) uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.eventCount[evt]
}

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
	ToxicityScore float64
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

type MessageSent struct{}
