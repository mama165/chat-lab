package workers

import (
	"chat-lab/contract"
	"chat-lab/errors"
	"context"
	"fmt"
	"log/slog"
	"sync"
	"time"
)

const waitTimeBeforeRestart = 200 * time.Millisecond

// Supervisor Own a context and a cancel function
// Run each worker in a goroutine
// Check panics and errors
// Restart workers automatically
// Shutdown properly if parent context is canceled
// Wait for the end of all goroutines via WaitGroup
type Supervisor struct {
	cancel  context.CancelFunc // To stop the context
	wg      *sync.WaitGroup    // Wait for the end of goroutines
	log     *slog.Logger
	workers []contract.Worker
}

func NewSupervisor(wg *sync.WaitGroup, log *slog.Logger) *Supervisor {
	return &Supervisor{wg: wg, log: log}
}

// Run Create a local cancellation trigger tied to the parent ctx
//
//	// If the parent (main) cancels, we cancel.
//	// If WE call s.cancel(), only our children cancel.
func (s *Supervisor) Run(ctx context.Context) {
	// 1. We create a local cancellation trigger tied to the parent ctx
	// If the parent (main) cancels, we cancel.
	// If WE call s.cancel(), only our children cancel.
	supervisedCtx, cancel := context.WithCancel(ctx)
	s.cancel = cancel
	// Safety: ensure resources are cleaned up when Run exits
	defer s.cancel()

	for _, worker := range s.workers {
		s.Start(supervisedCtx, worker)
	}
	s.wg.Wait()

}

func (s *Supervisor) Add(worker ...contract.Worker) contract.ISupervisor {
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

	go func() {
		defer s.wg.Done()

		for {
			if ctx.Err() != nil {
				s.log.Info(fmt.Sprintf("Stopping : %s", worker.GetName()))
				return
			}

			err := func() (err error) {
				defer func() {
					if r := recover(); r != nil {
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
				s.log.Info(fmt.Sprintf("Worker finished : %s", worker.GetName()))
				return
			}

			s.log.Info(fmt.Sprintf("Restarting : %s", worker.GetName()))
			time.Sleep(waitTimeBeforeRestart)
		}
	}()
}

// Stop cancel all goroutines listening channel for Ctx.Done
// Supervisor will wait for all goroutines to finish
func (s *Supervisor) Stop() {
	if s.cancel != nil {
		s.cancel()
	}
}
