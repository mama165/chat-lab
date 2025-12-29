package domain

import (
	"time"
)

type Command interface {
	RoomID() RoomID
}

type PostMessageCommand struct {
	Room      int
	SenderID  string
	Content   string
	CreatedAt time.Time
}

func (p PostMessageCommand) RoomID() RoomID {
	return RoomID(p.Room)
}
