package runtime

import (
	"chat-lab/domain/specialist"
	"chat-lab/errors"
	"chat-lab/infrastructure/grpc/client"
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

	"github.com/google/uuid"
	"google.golang.org/grpc"
)

// Coordinator handles the lifecycle and coordination of all specialist sidecars.
type Coordinator struct {
	mu          sync.RWMutex
	log         *slog.Logger
	specialists map[specialist.Metric]*client.SpecialistClient
}

func NewCoordinator(log *slog.Logger) *Coordinator {
	return &Coordinator{
		log:         log,
		specialists: make(map[specialist.Metric]*client.SpecialistClient),
	}
}

// Add integrates a new specialist (process + grpc client) into the pool.
func (m *Coordinator) Add(s *client.SpecialistClient) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.specialists[s.Id] = s
}

// Init launches all configured specialists and blocks until they are all ready.
func (m *Coordinator) Init(ctx context.Context, configs []specialist.Config, logLevel string) error {
	m.mu.Lock()
	defer m.mu.Unlock()

	for _, cfg := range configs {
		// Start each specialist using our robust launcher.
		// If one fails, we return the error to the Master to decide what to do.
		sClient, err := startSpecialist(ctx, cfg, logLevel)
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
func startSpecialist(ctx context.Context, cfg specialist.Config, logLevel string) (*client.SpecialistClient, error) {
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
		"-level", logLevel,
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

// Broadcast orchestrates the analysis of a file by streaming its content to all registered
// specialists in parallel. It reads the file from the provided path, dispatches analysis
// requests concurrently, and aggregates the results into an AnalysisResponse.
func (m *Coordinator) Broadcast(ctx context.Context, req specialist.AnalysisRequest) (specialist.AnalysisResponse, error) {
	data, err := os.ReadFile(req.Path)
	if err != nil {
		return specialist.AnalysisResponse{}, fmt.Errorf("failed to read file %s: %w", req.Path, err)
	}

	m.mu.RLock()
	numSpecs := len(m.specialists)
	m.mu.RUnlock()

	if numSpecs == 0 {
		return specialist.AnalysisResponse{Results: make(map[specialist.Metric]specialist.Response)}, nil
	}

	results := make(map[specialist.Metric]specialist.Response)

	// On utilise une structure interne pour le channel afin de capturer l'ID et la réponse
	type specResult struct {
		id   specialist.Metric
		resp specialist.Response
		err  error
	}
	resChan := make(chan specResult, numSpecs)
	var wg sync.WaitGroup

	// 3. Lancement des analyses en parallèle
	m.mu.RLock()
	for id, spec := range m.specialists {
		wg.Add(1)
		go func(id specialist.Metric, s *client.SpecialistClient) {
			defer wg.Done()

			domainReq := specialist.Request{
				Metadata: &specialist.Metadata{
					MessageID: uuid.New().String(),
					FileName:  req.Path,
					MimeType:  req.MimeType,
				},
				Chunk: data,
			}

			resp, err := s.Analyze(ctx, domainReq)
			if err != nil {
				m.log.Error("Specialist analysis failed", "id", id, "path", req.Path, "error", err)
				resChan <- specResult{id: id, err: err}
				return
			}

			resChan <- specResult{id: id, resp: resp}
		}(id, spec)
	}
	m.mu.RUnlock()

	go func() {
		wg.Wait()
		close(resChan)
	}()

	for res := range resChan {
		if res.err == nil {
			results[res.id] = res.resp
		}
	}

	return specialist.AnalysisResponse{Results: results}, nil
}
