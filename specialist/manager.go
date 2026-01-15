package specialist

import (
	"chat-lab/domain"
	"chat-lab/grpc/client"
	"context"
	"fmt"
	"log/slog"
	"sync"
)

// Manager handles the lifecycle and coordination of all specialist sidecars.
type Manager struct {
	mu          sync.RWMutex
	log         *slog.Logger
	specialists map[domain.SpecialistID]*client.SpecialistClient
}

func NewManager(log *slog.Logger) *Manager {
	return &Manager{
		log:         log,
		specialists: make(map[domain.SpecialistID]*client.SpecialistClient),
	}
}

// Add integrates a new specialist (process + grpc client) into the pool.
func (m *Manager) Add(s *client.SpecialistClient) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.specialists[s.Id] = s
}

// Init launches all configured specialists and blocks until they are all ready.
func (m *Manager) Init(ctx context.Context, configs []domain.SpecialistConfig) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, cfg := range configs {
		// Start each specialist using our robust launcher.
		// If one fails, we return the error to the Master to decide what to do.
		sClient, err := StartSpecialist(ctx, cfg)
		if err != nil {
			return fmt.Errorf("failed to initialize specialist %s: %w", cfg.ID, err)
		}

		m.specialists[cfg.ID] = sClient
		m.log.Info(fmt.Sprintf("Specialist %s is ready on port %d (PID: %d)\n", cfg.ID, cfg.Port, sClient.Process.Pid))
	}
	return nil
}
