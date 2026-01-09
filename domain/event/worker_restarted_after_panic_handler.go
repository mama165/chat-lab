package event

import (
	"chat-lab/errors"
	"fmt"
	"log/slog"
	"sync"
)

// WorkerRestartedAfterPanicHandler handles events when a worker panics and is restarted.
// It is triggered by the Supervisor when a worker recovers from a panic.
// Useful for monitoring reliability and resilience of the system.
type WorkerRestartedAfterPanicHandler struct {
	log     *slog.Logger
	mu      sync.Mutex
	counter *Counter
}

func NewWorkerRestartedAfterPanicHandler(log *slog.Logger, counter *Counter) *WorkerRestartedAfterPanicHandler {
	return &WorkerRestartedAfterPanicHandler{
		log:     log,
		counter: counter,
	}
}

func (h *WorkerRestartedAfterPanicHandler) Handle(event Event) {
	switch event.Type {
	case RestartedAfterPanicType:
		payload, ok := event.Payload.(WorkerRestartedAfterPanic)
		if !ok {
			h.log.Error(errors.ErrInvalidPayload.Error())
			return
		}
		h.mu.Lock()
		defer h.mu.Unlock()
		h.counter.Increment(RestartedAfterPanicType)
		h.log.Debug(fmt.Sprintf("Worker %s restarted after panic, total: %d", payload.WorkerName, h.counter.Get(RestartedAfterPanicType)))
	}
}
