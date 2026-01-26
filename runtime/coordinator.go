package runtime

import (
	"chat-lab/domain/specialist"
	"chat-lab/errors"
	"chat-lab/infrastructure/grpc/client"
	pb "chat-lab/proto/specialist"
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
	"google.golang.org/grpc/connectivity"
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
	if _, err := os.Stat(cfg.BinPath); err != nil {
		return nil, fmt.Errorf("%w: %s", errors.ErrSpecialistNotFound, cfg.BinPath)
	}

	// On WSL/Linux : venv/bin/python
	// On Windows : venv/Scripts/python.exe
	pythonInterpreter := "./venv/bin/python3"

	// Préparer l'exécution : python3 path/to/script.py -id ...
	cmd := exec.CommandContext(ctx, pythonInterpreter, cfg.BinPath,
		"-id", string(cfg.ID),
		"-port", strconv.Itoa(cfg.Port),
		"-type", string(cfg.ID),
		"-level", logLevel,
	)

	// Inject environment variables if necessary
	cmd.Env = append(os.Environ(),
		"PYTHONPATH=.",
		"PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python",
	)

	log.Printf("Specialist binary called : %s", cmd.String())
	cmd.Stdout = os.Stdout
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("%w: %v", errors.ErrSpecialistStartFailed, err)
	}

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
		time.Now(),
		cfg.Capabilities)
	return specialistClient, nil
}

// dialWithRetry Retry connection to the specialist to handle container startup latency
func dialWithRetry(ctx context.Context, host string, port int) (*grpc.ClientConn, error) {
	addr := fmt.Sprintf("%s:%d", host, port)

	opts := []grpc.DialOption{grpc.WithInsecure()}

	conn, err := grpc.DialContext(ctx, addr, opts...)
	if err != nil {
		return nil, fmt.Errorf("failed to dial: %w", err)
	}

	// On attend que l'état passe à "Ready" avec un petit timeout
	for i := 0; i < 60; i++ {
		state := conn.GetState()
		if state == connectivity.Ready {
			return conn, nil
		}

		// Si l'état est en échec, on tente de reconnecter
		if state == connectivity.TransientFailure || state == connectivity.Shutdown {
			conn.ResetConnectBackoff()
		}

		time.Sleep(500 * time.Millisecond)
	}

	err = conn.Close()
	if err != nil {
		return nil, fmt.Errorf("failed to close connection: %w", err)
	}
	return nil, fmt.Errorf("timeout: specialist at %s stay in state %s", addr, conn.GetState())
}

// Broadcast orchestrates the analysis of a file by streaming its content to relevant
// specialists in parallel based on their capabilities (MIME types).
func (m *Coordinator) Broadcast(ctx context.Context, req specialist.AnalysisRequest) (specialist.AnalysisResponse, error) {
	// 1. Read file content once to share it across specialists
	data, err := os.ReadFile(req.Path)
	if err != nil {
		return specialist.AnalysisResponse{}, fmt.Errorf("failed to read file %s: %w", req.Path, err)
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	var targets []*client.SpecialistClient
	for _, spec := range m.specialists {
		if spec.CanHandle(req.MimeType) {
			targets = append(targets, spec)
		}
	}

	if len(targets) == 0 {
		m.log.Warn("No specialist found for MIME type", "mime", req.MimeType, "path", req.Path)
		return specialist.AnalysisResponse{Results: make(map[specialist.Metric]specialist.Response)}, nil
	}

	type specResult struct {
		id   specialist.Metric
		resp specialist.Response
		err  error
	}

	resChan := make(chan specResult, len(targets))
	var wg sync.WaitGroup

	for _, spec := range targets {
		wg.Add(1)
		go func(s *client.SpecialistClient) {
			defer wg.Done()

			domainReq := specialist.Request{
				Metadata: &specialist.Metadata{
					MessageID: uuid.New().String(),
					FileName:  req.Path,
					MimeType:  req.MimeType,
				},
				Chunk: data,
			}

			// Call gRPC Analyze (stream or unary depending on your client impl)
			resp, err := s.Analyze(ctx, domainReq)
			if err != nil {
				m.log.Error("Specialist analysis failed",
					"id", s.Id,
					"mime", req.MimeType,
					"error", err,
				)
				resChan <- specResult{id: s.Id, err: err}
				return
			}

			resChan <- specResult{id: s.Id, resp: resp}
		}(spec)
	}

	// 5. Close results channel once all specialists have responded or failed
	go func() {
		wg.Wait()
		close(resChan)
	}()

	// 6. Aggregate results into the final response map
	results := make(map[specialist.Metric]specialist.Response)
	for res := range resChan {
		if res.err == nil {
			results[res.id] = res.resp
		}
	}

	return specialist.AnalysisResponse{Results: results}, nil
}
