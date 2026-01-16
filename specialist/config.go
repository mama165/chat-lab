package specialist

import (
	"chat-lab/domain"
	"chat-lab/errors"
	"chat-lab/grpc/client"
	pb "chat-lab/proto/analysis"
	"context"
	"fmt"
	"os"
	"os/exec"
	"strconv"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/backoff"
	"google.golang.org/grpc/connectivity"
	"google.golang.org/grpc/credentials/insecure"
)

// StartSpecialist orchestrates the full lifecycle of a sidecar process.
// It performs a "fail-fast" check on the binary, executes it as a child process
// linked to the provided context, and ensures the gRPC server is ready before
// returning a functional specialist client.
// This is a key component for Topic 3 (Supervisor Health).
func StartSpecialist(ctx context.Context, cfg domain.SpecialistConfig) (*client.SpecialistClient, error) {
	// 1. Validate binary existence using our custom error
	if _, err := os.Stat(cfg.BinPath); err != nil {
		return nil, fmt.Errorf("%w: %s", errors.ErrSpecialistNotFound, cfg.BinPath)
	}

	// 2. Prepare the execution command
	// We pass the port to the specialist so it knows where to listen.
	cmd := exec.CommandContext(ctx, cfg.BinPath, "--port", strconv.Itoa(cfg.Port))
	cmd.Stderr = os.Stderr
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("%w: %v", errors.ErrSpecialistStartFailed, err)
	}

	conn, err := dialWithRetry(ctx, cfg.Host, cfg.Port)
	if err != nil {
		// Cleanup: prevent zombie processes if the gRPC handshake fails.
		_ = cmd.Process.Kill()
		return nil, fmt.Errorf("%w on %s port %d: %v", errors.ErrSpecialistUnavailable, cfg.Host, cfg.Port, err)
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

// dialWithRetry initializes a gRPC client connection with a custom backoff strategy.
// Unlike the deprecated WithBlock option, it manually monitors the connection state
// to ensure the sidecar is fully 'READY' before returning.
// This prevents "cold-start" errors where the Master sends messages before
// the Specialist has finished loading its resources (e.g., ML models).
func dialWithRetry(ctx context.Context, host string, port int) (*grpc.ClientConn, error) {
	addr := fmt.Sprintf("%s:%d", host, port)

	// 1. Modern non-blocking client initialization
	// We add a ConnectParams with a Backoff strategy for long-term resilience.
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithConnectParams(grpc.ConnectParams{
			Backoff: backoff.Config{
				BaseDelay:  100 * time.Millisecond,
				Multiplier: 1.6,
				Jitter:     0.2,
				MaxDelay:   3 * time.Second,
			},
		}),
	)
	if err != nil {
		return nil, err
	}

	// 2. Active readiness check (The "Ideal" replacement for WithBlock)
	// We wait for the state to become 'Ready' within a 5s timeout.
	dialCtx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()

	for {
		state := conn.GetState()
		if state == connectivity.Ready {
			break
		}
		// WaitForStateChange is the clean way to block until something happens
		if !conn.WaitForStateChange(dialCtx, state) {
			return nil, errors.ErrSpecialistUnavailable
		}
	}

	return conn, nil
}
