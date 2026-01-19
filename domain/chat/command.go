package chat

import (
	"time"
)

type Command interface {
	RoomID() RoomID
}

type PostMessageCommand struct {
	Room      int
	UserID    string
	Content   string
	CreatedAt time.Time
}

func (p PostMessageCommand) RoomID() RoomID {
	return RoomID(p.Room)
}

type GetMessageCommand struct {
	Room   int
	Cursor *string
}

func (p GetMessageCommand) RoomID() RoomID {
	return RoomID(p.Room)
}
