package grpc

import (
	"chat-lab/domain/event"
	"context"
)

type Sink struct {
	ConnectedUserEvent chan event.DomainEvent
}

func NewGrpcSink(bufferSize int) *Sink {
	return &Sink{ConnectedUserEvent: make(chan event.DomainEvent, bufferSize)}
}

// Consume is called by fanout
// Redirect the event through the concerned owner of the channel
// The gRPC handler will take it from now
func (s *Sink) Consume(ctx context.Context, e event.DomainEvent) error {
	select {
	case s.ConnectedUserEvent <- e:
		return nil
	case <-ctx.Done():
		return ctx.Err()
	default:
		// Si le canal est plein, on pourrait logger une erreur de "Backpressure"
		return nil
	}
}
