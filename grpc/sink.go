package grpc

import (
	"chat-lab/domain/event"
	"context"
	"log/slog"
	"time"
)

type Sink struct {
	log                *slog.Logger
	connectedUserEvent chan event.DomainEvent
	deliveryTimeout    time.Duration
}

func NewGrpcSink(log *slog.Logger, bufferSize int, deliveryTimeout time.Duration) *Sink {
	return &Sink{
		connectedUserEvent: make(chan event.DomainEvent, bufferSize),
		log:                log,
		deliveryTimeout:    deliveryTimeout,
	}
}

// Consume attempts to deliver an event to the gRPC stream buffer.
// It uses a short grace period to absorb transient network jitters.
// If the buffer remains full, the message is dropped to prevent a single
// slow consumer from blocking the entire fan-out pipeline.
// A very tight timeout (e.g., 5-10ms) is key here to maintain
// high throughput for other connected users in the same room.
func (s *Sink) Consume(ctx context.Context, e event.DomainEvent) error {
	t := time.NewTimer(s.deliveryTimeout)
	defer t.Stop()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case s.connectedUserEvent <- e:
		return nil
	case <-t.C:
		s.log.Warn("sink overflow: dropping event to preserve system stability",
			"room_id", e.RoomID())
		return nil
	}
}
