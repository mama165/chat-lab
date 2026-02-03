package runtime

import (
	"chat-lab/domain/specialist"
	"chat-lab/errors"
	"chat-lab/infrastructure/grpc/client"
	"chat-lab/internal"
	pb "chat-lab/proto/specialist"
	"context"
	"fmt"
	"log/slog"
	"os"
	"os/exec"
	"runtime"
	"strconv"
	"sync"
	"time"

	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
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
func (m *Coordinator) Init(ctx context.Context, configs []specialist.Config, logLevel string, grpcConfig internal.GrpcConfig) error {
	for _, cfg := range configs {
		// Start each specialist using our robust launcher.
		// If one fails, we return the error to the Master to decide what to do.
		sClient, err := startSpecialist(ctx, cfg, logLevel, grpcConfig)
		if err != nil {
			return fmt.Errorf("failed to initialize specialist %s: %w", cfg.ID, err)
		}

		m.Add(sClient)
		m.log.Info(fmt.Sprintf("Specialist %s is ready on port %d (PID: %d)\n", cfg.ID, cfg.Port, sClient.Process.Pid))
	}
	return nil
}

// startSpecialist orchestrates the full lifecycle of a specialist sidecar process.
// It verifies the binary existence, determines the correct Python interpreter based on the OS,
// launches the process with the required environment variables, and establishes a gRPC
// connection with a native retry policy. If the gRPC handshake fails, it ensures
// the child process is killed to prevent zombie processes.
func startSpecialist(ctx context.Context, cfg specialist.Config, logLevel string, grpcCfg internal.GrpcConfig) (*client.SpecialistClient, error) {
	if _, err := os.Stat(cfg.BinPath); err != nil {
		return nil, fmt.Errorf("%w: %s", errors.ErrSpecialistNotFound, cfg.BinPath)
	}

	// Prepare interpreter (Depending on OS)
	pythonInterpreter := "./venv/bin/python3"
	if runtime.GOOS == "windows" {
		pythonInterpreter = ".\\venv\\Scripts\\python.exe"
	}

	cmd := exec.CommandContext(ctx, pythonInterpreter, cfg.BinPath,
		"-id", string(cfg.ID),
		"-port", strconv.Itoa(cfg.Port),
		"-type", string(cfg.ID),
		"-level", logLevel,
	)

	cmd.Env = append(os.Environ(),
		"PYTHONPATH=.",
		"PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python",
	)

	// Redirect stdout/stderr through our prefixed logger
	cmd.Stdout = &specialistLogWriter{logger: slog.Default(), prefix: string(cfg.ID), isError: false}
	cmd.Stderr = &specialistLogWriter{logger: slog.Default(), prefix: string(cfg.ID), isError: true}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("%w: %v", errors.ErrSpecialistStartFailed, err)
	}

	// gRPC connection with native retry
	serviceName := pb.SpecialistService_ServiceDesc.ServiceName
	retryConfig := grpcCfg.ToServiceConfig(serviceName)

	addr := fmt.Sprintf("%s:%d", cfg.Host, cfg.Port)
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithDefaultServiceConfig(retryConfig),
	)
	if err != nil {
		_ = cmd.Process.Kill()
		return nil, fmt.Errorf("failed to create gRPC client for %s: %w", cfg.ID, err)
	}

	// 5. Attente de la disponibilité réelle du serveur gRPC Python
	// On bloque ici pour que Init() ne rende la main que quand tout est prêt
	if err := waitForReady(ctx, conn, 30*time.Second); err != nil {
		_ = cmd.Process.Kill()
		_ = conn.Close()
		return nil, fmt.Errorf("specialist %s (port %d) failed to become ready: %w", cfg.ID, cfg.Port, err)
	}

	return client.NewSpecialistClient(
		cfg.ID,
		pb.NewSpecialistServiceClient(conn),
		cmd.Process,
		cfg.Port,
		time.Now(),
		cfg.Capabilities), nil
}

// waitForReady blocks until the gRPC connection state becomes READY or the timeout is reached.
// It monitors the connection's state machine and triggers immediate reconnection attempts
// if a transient failure is detected. This ensures the specialist's gRPC server is
// fully operational before the Orchestrator starts sending analysis requests.
func waitForReady(ctx context.Context, conn *grpc.ClientConn, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(ctx, timeout)
	defer cancel()

	for {
		state := conn.GetState()
		if state == connectivity.Ready {
			return nil
		}

		if state == connectivity.TransientFailure {
			conn.ResetConnectBackoff()
		}

		if !conn.WaitForStateChange(ctx, state) {
			return fmt.Errorf("timeout reached while waiting for state %s", state)
		}
	}
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
