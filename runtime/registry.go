package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"sync"
)

type Set map[string]struct{}

type Registry struct {
	mu          sync.RWMutex
	sessions    map[string]contract.EventSink // map participant -> Sink
	roomMembers map[domain.RoomID]Set         // map room to users
}

func NewRegistry() *Registry {
	return &Registry{
		sessions:    make(map[string]contract.EventSink),
		roomMembers: make(map[domain.RoomID]Set),
	}
}

// GetSinksForRoom retrieves all active communication channels for a specific room.
// It performs a two-step lookup:
// 1. Identifies participant IDs associated with the room via roomMembers.
// 2. Resolves those IDs into actual EventSinks using the sessions map.
//
// This decoupled approach ensures that even if a user is in multiple rooms,
// their connection (Sink) is managed in a single place.
// Returns nil if the room doesn't exist or has no members.
func (r *Registry) GetSinksForRoom(roomID domain.RoomID) []contract.EventSink {
	r.mu.RLock()
	defer r.mu.RUnlock()

	members, ok := r.roomMembers[roomID]
	if !ok {
		return nil
	}
	var activeSinks []contract.EventSink
	for participantID := range members {
		if sink, exists := r.sessions[participantID]; exists {
			activeSinks = append(activeSinks, sink)
		}
	}
	return activeSinks
}

// Subscribe registers a participant's active connection and assigns them to a specific room.
// It ensures thread-safe updates to both the global session directory and the room-specific membership set.
// If the room does not yet exist in the registry, it is initialized on the fly.
func (r *Registry) Subscribe(participantID string, roomID domain.RoomID, sink contract.EventSink) {
	r.mu.Lock()
	defer r.mu.Unlock()

	r.sessions[participantID] = sink

	if _, ok := r.roomMembers[roomID]; !ok {
		r.roomMembers[roomID] = make(Set)
	}
	r.roomMembers[roomID][participantID] = struct{}{}
}

// Unsubscribe removes a participant from the registry and their current room.
// It cleans up the session and ensures no empty sets are left in the room map
// to prevent memory leaks over time.
func (r *Registry) Unsubscribe(participantID string, roomID domain.RoomID) {
	r.mu.Lock()
	defer r.mu.Unlock()

	delete(r.sessions, participantID)

	if members, ok := r.roomMembers[roomID]; ok {
		delete(members, participantID)

		// If no one is left in the room, remove the room entry entirely
		if len(members) == 0 {
			delete(r.roomMembers, roomID)
		}
	}
}
