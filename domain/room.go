package domain

type RoomID int

type Room struct {
	ID RoomID
}

func NewRoom(id int) *Room {
	return &Room{
		ID: RoomID(id),
	}
}
