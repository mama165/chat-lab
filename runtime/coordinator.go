package runtime

import (
	"chat-lab/domain"
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

	"github.com/go-playground/validator/v10"
	"github.com/google/uuid"
	"google.golang.org/grpc"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
)

// Coordinator handles the lifecycle and coordination of all specialist sidecars.
type Coordinator struct {
	mu                     sync.RWMutex
	log                    *slog.Logger
	specialists            map[domain.Metric]*client.SpecialistClient
	processTrackerChan     chan domain.Process
	specialistResponseChan chan domain.SpecialistResponse
	maxFileSizeMB          int
	validator              *validator.Validate
}

func NewCoordinator(
	log *slog.Logger,
	processTrackerChan chan domain.Process,
	specialistResponseChan chan domain.SpecialistResponse,
	maxFileSizeMB int) *Coordinator {
	return &Coordinator{
		log:                    log,
		specialists:            make(map[domain.Metric]*client.SpecialistClient),
		processTrackerChan:     processTrackerChan,
		specialistResponseChan: specialistResponseChan,
		maxFileSizeMB:          maxFileSizeMB,
		validator:              validator.New(),
	}
}

// Add integrates a new specialist (process + grpc client) into the pool.
func (m *Coordinator) Add(s *client.SpecialistClient) {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.specialists[s.Id] = s
}

// Init launches all configured specialists and blocks until they are all ready.
func (m *Coordinator) Init(appCtx, bootCtx context.Context, configs []domain.Config, logLevel string, grpcConfig internal.GrpcConfig) error {
	for _, cfg := range configs {
		// Start each specialist using our robust launcher.
		// If one fails, we return the error to the Master to decide what to do.
		sClient, err := startSpecialist(appCtx, bootCtx, cfg, logLevel, grpcConfig)
		if err != nil {
			return fmt.Errorf("failed to initialize specialist %s: %w", cfg.ID, err)
		}

		m.Add(sClient)
		m.log.Info(fmt.Sprintf("Specialist %s is ready on port %d (PID: %d)\n", cfg.ID, cfg.Port, sClient.Process.Pid))

		select {
		case <-appCtx.Done():
			return appCtx.Err()
		case m.processTrackerChan <- domain.Process{PID: domain.PID(sClient.Process.Pid), Metric: sClient.Id}:
			m.log.Info("Registering process for health monitoring worker", "PID", sClient.Process.Pid)
		}
	}
	return nil
}

// startSpecialist orchestrates the full lifecycle of a specialist sidecar process.
// It uses appCtx to ensure the child process survival for the entire application lifetime,
// while bootCtx is used to bound the gRPC handshake and readiness check.
// If the gRPC connection is not established within the bootCtx deadline, the process
// is killed to prevent orphan sidecars.
func startSpecialist(appCtx, bootCtx context.Context, cfg domain.Config, logLevel string, grpcCfg internal.GrpcConfig) (*client.SpecialistClient, error) {
	if _, err := os.Stat(cfg.BinPath); err != nil {
		return nil, fmt.Errorf("%w: %s", errors.ErrSpecialistNotFound, cfg.BinPath)
	}

	// Prepare interpreter (Depending on OS)
	pythonInterpreter := "./venv/bin/python3"
	if runtime.GOOS == "windows" {
		pythonInterpreter = ".\\venv\\Scripts\\python.exe"
	}

	cmd := exec.CommandContext(appCtx, pythonInterpreter, cfg.BinPath,
		"-id", string(cfg.ID),
		"-port", strconv.Itoa(cfg.Port),
		"-level", logLevel,
	)

	// Send a signal SIGKILL to children (python) if father (go) dies suddenly
	setPlatformSpecificAttrs(cmd)

	cmd.Env = append(os.Environ(),
		"PYTHONPATH=.",
		"PROTOCOL_BUFFERS_PYTHON_IMPLEMENTATION=python",
	)

	// CRITICAL: Ensure the command runs from the project root
	cwd, err := os.Getwd()
	if err != nil {
		return nil, fmt.Errorf("failed to get current working directory: %w", err)
	}
	cmd.Dir = cwd

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

	// Waiting for reliable availability from gRPC python/C++ server
	// Blocking until Init() release when ready
	if err := waitForReady(bootCtx, conn, 30*time.Second); err != nil {
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

// waitForReady blocks until the gRPC connection state becomes READY.
// It uses conn.Connect() to proactively trigger the transition from IDLE to CONNECTING,
// as gRPC-Go clients are "lazy" by default and won't connect until a request is made.
// It respects the bootCtx deadline for the initial handshake.
func waitForReady(bootCtx context.Context, conn *grpc.ClientConn, timeout time.Duration) error {
	ctx, cancel := context.WithTimeout(bootCtx, timeout)
	defer cancel()

	// Force connection instead of waiting for IDLE
	conn.Connect()

	for {
		state := conn.GetState()
		if state == connectivity.Ready {
			return nil
		}

		// If IDLE -> technically ready to attempt for a connection
		// But Go often waits for an RPC to be READY
		if state == connectivity.Idle {
			// Attempting a connection to force passage to CONNECTING/READY
			conn.Connect()
		}

		if state == connectivity.TransientFailure {
			conn.ResetConnectBackoff()
		}

		// If context is expired -> returns error with actual connection state
		if !conn.WaitForStateChange(ctx, state) {
			return fmt.Errorf("timeout reached while waiting, last state: %s", state)
		}
	}
}

// Broadcast orchestrates the analysis of a file by streaming its content to relevant
// specialists in parallel based on their capabilities (MIME types).
func (m *Coordinator) Broadcast(ctx context.Context, req domain.SpecialistRequest) error {
	if err := m.validator.Struct(req); err != nil {
		return err
	}

	fileInfo, err := os.Stat(req.Path)
	if err != nil {
		return fmt.Errorf("file not found at : %s, err : %w", req.Path, err)
	}

	size := fileInfo.Size()
	if size > int64(m.maxFileSizeMB) {
		return fmt.Errorf("file %s is too large (%d bytes)", req.Path, size)
	}

	data, err := os.ReadFile(req.Path)
	if err != nil {
		return fmt.Errorf("failed to read file %s: %w", req.Path, err)
	}

	m.mu.RLock()
	defer m.mu.RUnlock()

	var targets []*client.SpecialistClient
	for _, spec := range m.specialists {
		if spec.CanHandle(req.EffectiveMimeType) {
			targets = append(targets, spec)
		}
	}

	if len(targets) == 0 {
		m.log.Warn("No specialist found for MIME type", "mime", req.EffectiveMimeType, "path", req.Path)
		return nil
	}

	var wg sync.WaitGroup
	results := make([]domain.SpecialistResult, len(targets))

	for _, spec := range targets {
		wg.Add(1)
		go func(specialist *client.SpecialistClient) {
			domainReq := domain.Request{
				Metadata: &domain.Metadata{
					MessageID:         uuid.New().String(),
					FileName:          req.Path,
					EffectiveMimeType: req.EffectiveMimeType,
				},
				Chunk: data,
			}

			go func(request domain.Request) {
				defer wg.Done()
				resp, err := specialist.Analyze(ctx, domainReq)
				if err != nil {
					m.log.Error("Specialist analysis failed",
						"id", specialist.Id,
						"mimeType", req.EffectiveMimeType,
						"error", err,
					)

					results = append(results, domain.SpecialistResult{ID: &specialist.Id, SpecialistErr: err})
					return
				}
				results = append(results, domain.SpecialistResult{ID: &specialist.Id, Resp: &resp})
			}(domainReq)
		}(spec)
	}

	go func(res []domain.SpecialistResult) {
		wg.Wait()
		select {
		case <-ctx.Done():
			m.log.Warn("Giving up sending results, context canceled", "fileID", req.FileID)
			return
		case m.specialistResponseChan <- domain.SpecialistResponse{
			FileID: req.FileID,
			Res:    res,
		}:
		}
		m.log.Debug("All specialist terminated", "targets", len(targets))
	}(results)

	return nil
}
