package chat

type RoomID int

type Room struct {
	ID RoomID
}

func NewRoom(id RoomID) *Room {
	return &Room{
		ID: id,
	}
}
