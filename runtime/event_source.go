package runtime

import "chat-lab/domain/event"

type EventSource interface {
	PullEvents() []event.DomainEvent
}
