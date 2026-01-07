package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"testing"
)

type Sink struct {
}

func (s Sink) Consume(ctx context.Context, e event.DomainEvent) error {
	return nil
}

func TestRegistry_Subscribe_One_Room_One_Participant(t *testing.T) {
	req := require.New(t)
	registry := NewRegistry()
	participantID := uuid.NewString()
	roomID := domain.RoomID(1)
	sink := Sink{}

	// Given no user is connected
	// And no room exists
	req.Empty(registry.Sessions)
	req.Empty(registry.RoomMembers)

	// When a participant subscribes a room
	registry.Subscribe(participantID, roomID, sink)

	// Then
	req.Len(registry.Sessions, 1)
	req.Equal(sink, registry.Sessions[participantID])

	req.Len(registry.RoomMembers, 1)
	req.Contains(registry.RoomMembers[roomID], participantID)

	req.Len(registry.GetSinksForRoom(roomID), 1)
	req.Contains(registry.GetSinksForRoom(roomID), sink)
}

func TestRegistry_Subscribe_One_Room_Multiple_Participants(t *testing.T) {
	req := require.New(t)
	registry := NewRegistry()
	participantID1 := uuid.NewString()
	participantID2 := uuid.NewString()
	roomID := domain.RoomID(1)
	sink1 := Sink{}
	sink2 := Sink{}

	// When participants subscribe a room
	registry.Subscribe(participantID1, roomID, sink1)
	registry.Subscribe(participantID2, roomID, sink2)

	// Then
	req.Len(registry.Sessions, 2)
	req.Len(registry.RoomMembers[roomID], 2)

	req.Len(registry.GetSinksForRoom(roomID), 2)
	req.Contains(registry.GetSinksForRoom(roomID), sink1)
}

func TestRegistry_UnSubscribe_One_Room_One_Participant(t *testing.T) {
	req := require.New(t)
	registry := NewRegistry()
	participantID := uuid.NewString()
	roomID := domain.RoomID(1)
	sink := Sink{}

	// Given a participant subscribes a room
	registry.Subscribe(participantID, roomID, sink)

	// When a participant unsubscribe a room
	registry.Unsubscribe(participantID, roomID)

	// Then no participant left
	// And the room doesn't exist anymore
	req.Empty(registry.Sessions)
	req.Empty(registry.RoomMembers)

	// And no participant connected left in room
	req.Nil(registry.GetSinksForRoom(roomID))
}

func TestRegistry_UnSubscribe_One_Room_Multiple_Participant(t *testing.T) {
	req := require.New(t)
	registry := NewRegistry()
	participantID1 := uuid.NewString()
	participantID2 := uuid.NewString()
	roomID := domain.RoomID(1)
	sink1 := Sink{}
	sink2 := Sink{}

	// When participants subscribe a room
	registry.Subscribe(participantID1, roomID, sink1)
	registry.Subscribe(participantID2, roomID, sink2)

	// When a participant unsubscribe a room
	registry.Unsubscribe(participantID1, roomID)

	// Then only one participant left
	req.Len(registry.Sessions, 1)
	req.Len(registry.RoomMembers[roomID], 1)

	req.Len(registry.GetSinksForRoom(roomID), 1)
	req.Contains(registry.GetSinksForRoom(roomID), sink2)
}
