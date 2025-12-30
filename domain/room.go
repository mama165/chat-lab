package domain

type RoomID int

type Room struct {
	ID       RoomID
	messages []Message
}

func NewRoom(id int) *Room {
	return &Room{
		ID:       RoomID(id),
		messages: nil,
	}
}

func (r *Room) PostMessage(message Message) {
	r.messages = append(r.messages, message)
}
