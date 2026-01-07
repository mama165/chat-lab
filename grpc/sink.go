package grpc

import (
	"chat-lab/domain/event"
	"context"
	"log/slog"
)

type Sink struct {
	connectedUserEvent chan event.DomainEvent
	log                *slog.Logger
}

func NewGrpcSink(bufferSize int, log *slog.Logger) *Sink {
	return &Sink{
		connectedUserEvent: make(chan event.DomainEvent, bufferSize),
		log:                log,
	}
}

// Consume is called by fanout
// Redirect the event through the concerned owner of the channel
// The gRPC handler will take it from now
func (s *Sink) Consume(ctx context.Context, e event.DomainEvent) error {
	select {
	case <-ctx.Done():
		return ctx.Err()
	case s.connectedUserEvent <- e:
		return nil
	default:
		s.log.Warn("Sink gRPC is full, message dropped...")
		return nil
	}
}
