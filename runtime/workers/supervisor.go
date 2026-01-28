package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/errors"
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

// Supervisor Own a context and a Cancel function
// Run each worker in a goroutine
// Check panics and errors
// Restart workers automatically
// Shutdown properly if parent context is canceled
// Wait for the end of all goroutines via WaitGroup
type Supervisor struct {
	mu              sync.RWMutex
	Cancel          context.CancelFunc // To stop the context
	wg              *sync.WaitGroup    // Wait for the end of goroutines
	log             *slog.Logger
	telemetryChan   chan event.Event
	workers         []contract.Worker
	restartInterval time.Duration
}

func NewSupervisor(log *slog.Logger, telemetryChan chan event.Event, restartInterval time.Duration) *Supervisor {
	return &Supervisor{wg: &sync.WaitGroup{}, log: log,
		telemetryChan: telemetryChan, restartInterval: restartInterval,
	}
}

// Run Create a local cancellation trigger tied to the parent ctx
//
//	// If the parent (main) cancels, we Cancel.
//	// If WE call s.Cancel(), only our children Cancel.
func (s *Supervisor) Run(ctx context.Context) {
	// 1. We create a local cancellation trigger tied to the parent ctx
	// If the parent (main) cancels, we Cancel.
	// If WE call s.Cancel(), only our children Cancel.
	supervisedCtx, cancel := context.WithCancel(ctx)
	s.Cancel = cancel
	// Safety: ensure resources are cleaned up when Run exits
	defer s.Cancel()

	s.mu.RLock()
	workersToStart := make([]contract.Worker, len(s.workers))
	copy(workersToStart, s.workers)
	s.mu.RUnlock()

	for _, worker := range workersToStart {
		s.Start(supervisedCtx, worker)
	}
	s.wg.Wait()
}

func (s *Supervisor) Add(worker ...contract.Worker) contract.ISupervisor {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.workers = append(s.workers, worker...)
	return s
}

// Start runs a worker under supervision.
// The worker is executed in a dedicated goroutine. If its Run method panics,
// the supervisor recovers, restarts the worker, and keeps the supervision
// loop alive. A failure in one worker must not stop the supervisor itself.
// This provides fault isolation and basic self-healing behavior.
func (s *Supervisor) Start(ctx context.Context, worker contract.Worker) {
	s.wg.Add(1)
	workerName := contract.GetWorkerName(worker)

	go func() {
		defer s.wg.Done()

		for {
			if ctx.Err() != nil {
				s.log.Info(fmt.Sprintf("Stopping : %s", workerName))
				return
			}

			err := func() (err error) {
				defer func() {
					if r := recover(); r != nil {
						s.sendRestartEvent(ctx, workerName)
						err = errors.ErrWorkerPanic
					}
				}()
				// Execute the children goroutine
				// Restarted after a crash
				// Not restarting the entire goroutine
				return worker.Run(ctx)
			}()

			if err == nil {
				// Terminated properly, never restart !
				s.log.Info(fmt.Sprintf("Worker finished : %s", workerName))
				return
			}

			if ctx.Err() != nil {
				s.log.Info("Worker stopped (context canceled)", "name", workerName)
				return
			}

			s.log.Warn("Worker crashed, restarting", "name", workerName, "error", err)
			select {
			case <-ctx.Done():
				// Context canceled: priority stop.
				// Exit immediately without waiting for the restart delay.
				return
			case <-time.After(s.restartInterval):
				// Delay elapsed and context is still active.
				// Proceed with the worker restart.
			}
		}
	}()
}

// Stop Cancel all goroutines listening channel for Ctx.Done
// Supervisor will wait for all goroutines to finish
func (s *Supervisor) Stop() {
	if s.Cancel != nil {
		s.Cancel()
	}
}

func (s *Supervisor) sendRestartEvent(ctx context.Context, workerName string) {
	if s.telemetryChan == nil {
		s.log.Warn("Telemetry channel not initialized in Supervisor", "worker", workerName)
		return
	}
	select {
	case <-ctx.Done():
	case s.telemetryChan <- event.Event{
		Type:      event.RestartedAfterPanicType,
		CreatedAt: time.Now().UTC(),
		Payload: event.WorkerRestartedAfterPanic{
			WorkerName: workerName,
		},
	}:
	default:
		s.log.Debug("Observability telemetry eventChan lost")
	}
}
