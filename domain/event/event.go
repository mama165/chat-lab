package event

import (
	"sync"
	"time"
)

type Type string

// Event is the envelope carried by the internal channels (events, telemetry).
type Event struct {
	Type      Type
	CreatedAt time.Time
	Payload   any
}

// Counter keeps per-type event counts for telemetry handlers.
type Counter struct {
	mu         sync.Mutex
	eventCount map[Type]uint64
}

func NewCounter() *Counter {
	return &Counter{
		eventCount: make(map[Type]uint64),
	}
}

func (c *Counter) Increment(evt Type) {
	c.mu.Lock()
	defer c.mu.Unlock()
	c.eventCount[evt]++
}

func (c *Counter) Get(evt Type) uint64 {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.eventCount[evt]
}
