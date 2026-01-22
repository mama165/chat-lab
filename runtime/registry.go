package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain/chat"
	"sync"
)

type Set map[string]struct{}

// Registry Store map of participants
// Track participants per room
type Registry struct {
	mu          sync.RWMutex
	Sessions    map[string]contract.EventSink[contract.DomainEvent]
	RoomMembers map[chat.RoomID]Set
}

func NewRegistry() *Registry {
	return &Registry{
		Sessions:    make(map[string]contract.EventSink[contract.DomainEvent]),
		RoomMembers: make(map[chat.RoomID]Set),
	}
}

// GetSinksForRoom retrieves all active communication channels for a specific room.
// It performs a two-step lookup:
// 1. Identifies participant IDs associated with the room via RoomMembers.
// 2. Resolves those IDs into actual EventSinks using the Sessions map.
//
// This decoupled approach ensures that even if a user is in multiple rooms,
// their connection (Sink) is managed in a single place.
// Returns nil if the room doesn't exist or has no members.
func (r *Registry) GetSinksForRoom(roomID chat.RoomID) []contract.EventSink[contract.DomainEvent] {
	r.mu.RLock()
	defer r.mu.RUnlock()

	members, ok := r.RoomMembers[roomID]
	if !ok {
		return nil
	}
	var activeSinks []contract.EventSink[contract.DomainEvent]
	for participantID := range members {
		if sink, exists := r.Sessions[participantID]; exists {
			activeSinks = append(activeSinks, sink)
		}
	}
	return activeSinks
}

// Subscribe registers a participant's active connection and assigns them to a specific room.
// It ensures thread-safe updates to both the global session directory and the room-specific membership set.
// If the room does not yet exist in the registry, it is initialized on the fly.
func (r *Registry) Subscribe(participantID string,
	roomID chat.RoomID, sink contract.EventSink[contract.DomainEvent]) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.Sessions[participantID] = sink

	if _, ok := r.RoomMembers[roomID]; !ok {
		r.RoomMembers[roomID] = make(Set)
	}
	r.RoomMembers[roomID][participantID] = struct{}{}
}

// Unsubscribe removes a participant from the registry and their current room.
// It cleans up the session and ensures no empty sets are left in the room map
// to prevent memory leaks over time.
func (r *Registry) Unsubscribe(participantID string, roomID chat.RoomID) {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.Sessions, participantID)

	if members, ok := r.RoomMembers[roomID]; ok {
		delete(members, participantID)

		// If no one is left in the room, remove the room entry entirely
		if len(members) == 0 {
			delete(r.RoomMembers, roomID)
		}
	}
}
