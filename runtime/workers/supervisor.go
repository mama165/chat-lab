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

const waitTimeBeforeRestart = 200 * time.Millisecond

type ISupervisor interface {
	Run()
	Add(worker ...contract.Worker) ISupervisor
	Start(worker contract.Worker)
	Stop()
}

// Supervisor Own a context and a Cancel function
// Run each worker in a goroutine
// Check panics and errors
// Restart workers automatically
// Shutdown properly if parent context is canceled
// Wait for the end of all goroutines via WaitGroup
type Supervisor struct {
	Ctx     context.Context    // To communicate a stop to all workers
	Cancel  context.CancelFunc // To stop the context
	wg      *sync.WaitGroup    // Wait for the end of goroutines
	log     *slog.Logger
	workers []contract.Worker
	Event   chan event.DomainEvent
}

func NewSupervisor(ctx context.Context, cancel context.CancelFunc, wg *sync.WaitGroup, log *slog.Logger) Supervisor {
	return Supervisor{Ctx: ctx, Cancel: cancel, wg: wg, log: log}
}

func (s *Supervisor) Run() {
	for _, worker := range s.workers {
		s.Start(worker)
	}
}

func (s *Supervisor) Add(worker ...contract.Worker) ISupervisor {
	s.workers = append(s.workers, worker...)
	return s
}

// Start runs a worker under supervision.
// The worker is executed in a dedicated goroutine. If its Run method panics,
// the supervisor recovers, restarts the worker, and keeps the supervision
// loop alive. A failure in one worker must not stop the supervisor itself.
// This provides fault isolation and basic self-healing behavior.
func (s *Supervisor) Start(worker contract.Worker) {
	s.wg.Add(1)

	go func() {
		defer s.wg.Done()

		for {
			if s.Ctx.Err() != nil {
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
				return worker.Run(s.Ctx)
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

// Stop Cancel all goroutines listening channel for Ctx.Done
// Supervisor will wait for all goroutines to finish
func (s *Supervisor) Stop() {
	s.Cancel()
	s.wg.Wait()
}
