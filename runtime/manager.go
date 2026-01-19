package runtime

import (
	"chat-lab/domain/specialist"
	"chat-lab/errors"
	"chat-lab/grpc/client"
	pb "chat-lab/proto/analysis"
	"context"
	"fmt"
	"log"
	"log/slog"
	"os"
	"os/exec"
	"strconv"
	"sync"
	"time"

	"google.golang.org/grpc"
)

// Manager handles the lifecycle and coordination of all specialist sidecars.
type Manager struct {
	mu          sync.RWMutex
	log         *slog.Logger
	specialists map[specialist.Metric]*client.SpecialistClient
}

func NewManager(log *slog.Logger) *Manager {
	return &Manager{
		log:         log,
		specialists: make(map[specialist.Metric]*client.SpecialistClient),
	}
}

// Add integrates a new specialist (process + grpc client) into the pool.
func (m *Manager) Add(s *client.SpecialistClient) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.specialists[s.Id] = s
}

// Init launches all configured specialists and blocks until they are all ready.
func (m *Manager) Init(ctx context.Context, configs []specialist.Config) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, cfg := range configs {
		// Start each specialist using our robust launcher.
		// If one fails, we return the error to the Master to decide what to do.
		sClient, err := startSpecialist(ctx, cfg)
		if err != nil {
			return fmt.Errorf("failed to initialize specialist %s: %w", cfg.ID, err)
		}

		m.Add(sClient)
		m.log.Info(fmt.Sprintf("Specialist %s is ready on port %d (PID: %d)\n", cfg.ID, cfg.Port, sClient.Process.Pid))
	}
	return nil
}

// startSpecialist orchestrates the full lifecycle of a sidecar process.
// It performs a "fail-fast" check on the binary, executes it as a child process
// linked to the provided context, and ensures the gRPC server is ready before
// returning a functional specialist client.
func startSpecialist(ctx context.Context, cfg specialist.Config) (*client.SpecialistClient, error) {
	// 1. Validate binary existence using our custom error
	if _, err := os.Stat(cfg.BinPath); err != nil {
		return nil, fmt.Errorf("%w: %s", errors.ErrSpecialistNotFound, cfg.BinPath)
	}

	// 2. Prepare the execution command
	// We pass the port to the specialist so it knows where to listen.
	//cmd := exec.CommandContext(ctx, cfg.BinPath, "--port", strconv.Itoa(cfg.Port))
	cmd := exec.CommandContext(ctx, cfg.BinPath,
		"-id", string(cfg.ID),
		"-port", strconv.Itoa(cfg.Port),
		"-type", string(cfg.ID), // On utilise l'ID comme type pour l'instant
		"-level", "INFO",
	)
	log.Printf("Specialist binary called : %s", cmd.String())
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("%w: %v", errors.ErrSpecialistStartFailed, err)
	}

	time.Sleep(500 * time.Millisecond)

	conn, err := dialWithRetry(ctx, cfg.Host, cfg.Port)
	if err != nil {
		// Cleanup: prevent zombie processes if the gRPC handshake fails.
		_ = cmd.Process.Kill()
		return nil, fmt.Errorf("%w on port %d: %v", errors.ErrSpecialistUnavailable, cfg.Port, err)
	}

	// 5. Return the fully operational specialist client
	specialistClient := client.NewSpecialistClient(
		cfg.ID,
		pb.NewSpecialistServiceClient(conn),
		cmd.Process,
		cfg.Port,
		time.Now())
	return specialistClient, nil
}

// dialWithRetry Retry connection to the specialist to handle container startup latency
func dialWithRetry(ctx context.Context, host string, port int) (*grpc.ClientConn, error) {
	addr := fmt.Sprintf("%s:%d", host, port)
	// Try for 10 seconds, every 500ms
	for i := 0; i < 20; i++ {
		// Use insecure for now as per your modular chat plan
		conn, err := grpc.DialContext(ctx, addr, grpc.WithInsecure(), grpc.WithBlock())
		if err == nil {
			return conn, nil
		}
		time.Sleep(500 * time.Millisecond)
	}
	return nil, fmt.Errorf("timeout: specialist not responding at %s after retries", addr)
}

// AnalyzeAll broadcasts the content to all registered specialists in parallel.
// It implements the Fan-Out pattern to ensure minimum latency
func (m *Manager) AnalyzeAll(ctx context.Context, messageID string, content string) map[specialist.Metric]specialist.Response {
	m.mu.RLock()
	defer m.mu.RUnlock()

	results := make(map[specialist.Metric]specialist.Response)
	var mu sync.Mutex
	var wg sync.WaitGroup

	for id, spec := range m.specialists {
		wg.Add(1)
		go func(id specialist.Metric, s *client.SpecialistClient) {
			defer wg.Done()

			resp, err := s.Analyze(ctx, specialist.Request{
				MessageID: messageID,
				Content:   content,
			})

			if err != nil {
				m.log.Error("Specialist analysis failed", "id", id, "error", err)
				return
			}

			mu.Lock()
			results[id] = resp
			mu.Unlock()
		}(id, spec)
	}

	wg.Wait()
	return results
}
