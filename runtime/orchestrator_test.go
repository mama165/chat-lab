package runtime_test

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/runtime"
	"github.com/stretchr/testify/assert"
	"testing"
	"time"
)

type RecordingSink struct {
	events []event.DomainEvent
}

func (s *RecordingSink) Consume(e event.DomainEvent) {
	s.events = append(s.events, e)
}

func Test_Orchestrator_dispatches_domain_events_to_sinks(t *testing.T) {
	// Arrange
	orchestrator := runtime.NewOrchestrator()
	room := domain.NewRoom(1)

	sink := &RecordingSink{}

	orchestrator.RegisterRoom(room)
	orchestrator.RegisterSink(sink)

	msg := domain.Message{
		SenderID:  "alice",
		Content:   "hello world",
		CreatedAt: time.Now(),
	}

	// Act
	orchestrator.Dispatch(1, func(r *domain.Room) {
		r.PostMessage(msg)
	})

	// Assert
	assert.Len(t, sink.events, 1)

	evt, ok := sink.events[0].(event.MessagePosted)
	assert.True(t, ok, "event should be MessagePosted")

	assert.Equal(t, "alice", evt.Author)
	assert.Equal(t, "hello world", evt.Content)
}
