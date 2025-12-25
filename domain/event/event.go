package event

import "time"

type DomainEvent interface {
	Name() string
	OccurredAt() time.Time
}

type MessagePosted struct {
	Author  string
	Content string
	At      time.Time
}

func (m MessagePosted) Name() string {
	return "message_posted"
}

func (m MessagePosted) OccurredAt() time.Time {
	return m.At
}
