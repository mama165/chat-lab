package runtime

import "chat-lab/domain/event"

type EventSource interface {
	FlushEvents() []event.DomainEvent
}
