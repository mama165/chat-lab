package specialist

import (
	"chat-lab/domain"
	"chat-lab/grpc/client"
	"sync"
)

// Manager handles the lifecycle and coordination of all specialist sidecars.
type Manager struct {
	mu          sync.RWMutex
	specialists map[domain.SpecialistID]*client.SpecialistClient
}

func NewManager() *Manager {
	return &Manager{
		specialists: make(map[domain.SpecialistID]*client.SpecialistClient),
	}
}

// Add integrates a new specialist (process + grpc client) into the pool.
func (m *Manager) Add(s *client.SpecialistClient) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.specialists[s.Id] = s
}
