// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"bufio"
	"bytes"
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/errors"
	"chat-lab/moderation"
	"chat-lab/runtime/workers"
	"context"
	"embed"
	"fmt"
	"io/fs"
	"log/slog"
	"strings"
	"sync"
)

//go:embed censored/*
var censoredFolder embed.FS

type Orchestrator struct {
	mu              sync.Mutex
	log             *slog.Logger
	numWorkers      int
	rooms           map[domain.RoomID]*domain.Room
	sinks           []contract.EventSink
	supervisor      contract.ISupervisor
	globalCommands  chan domain.Command
	rawEvents       chan event.DomainEvent
	domainEvents    chan event.DomainEvent
	telemetryEvents chan event.DomainEvent
}

func NewOrchestrator(log *slog.Logger, supervisor *workers.Supervisor, numWorkers, bufferSize int) *Orchestrator {
	return &Orchestrator{
		log:             log,
		numWorkers:      numWorkers,
		rooms:           make(map[domain.RoomID]*domain.Room),
		sinks:           nil,
		supervisor:      supervisor,
		globalCommands:  make(chan domain.Command, bufferSize),
		rawEvents:       make(chan event.DomainEvent, bufferSize),
		domainEvents:    make(chan event.DomainEvent, bufferSize),
		telemetryEvents: make(chan event.DomainEvent, bufferSize),
	}
}

// RegisterRoom creates a dedicated command channel for a Room.
func (o *Orchestrator) RegisterRoom(room *domain.Room) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, ok := o.rooms[room.ID]; ok {
		o.log.Info(fmt.Sprintf("Room %d already exists", room.ID))
		return
	}
	o.rooms[room.ID] = room
}

func (o *Orchestrator) RegisterSinks(sink ...contract.EventSink) {
	o.sinks = append(o.sinks, sink...)
}

func (o *Orchestrator) Dispatch(cmd domain.Command) {
	select {
	case o.globalCommands <- cmd:
	default:
		o.log.Warn(fmt.Sprintf("Global command channel full for Room %d, dropping command", cmd.RoomID()))
	}
}

func (o *Orchestrator) Start(ctx context.Context) error {
	o.mu.Lock()

	for i := 0; i < o.numWorkers; i++ {
		worker := workers.NewPoolUnitWorker(o.rooms, o.globalCommands, o.rawEvents, o.log)
		o.supervisor.Add(worker)
	}
	entries, err := fs.ReadDir(censoredFolder, "censored")
	if err != nil {
		o.mu.Unlock()
		return err
	}
	var languages, words []string
	uniqueWords := make(map[string]struct{})
	for _, entry := range entries {
		if entry.IsDir() {
			o.mu.Unlock()
			return errors.ErrOnlyCensoredFiles
		}
		languages = append(languages, strings.TrimSuffix(entry.Name(), ".txt"))
		data, err := censoredFolder.ReadFile("censored/" + entry.Name())
		if err != nil {
			o.mu.Unlock()
			return err
		}
		// Use a scanner to properly read line (handle \n et \r\n)
		// ⚠️Don't use strings.Split
		scanner := bufio.NewScanner(bytes.NewReader(data))
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line != "" {
				uniqueWords[line] = struct{}{}
			}
		}
		if err = scanner.Err(); err != nil {
			o.mu.Unlock()
			return err
		}
	}
	for w := range uniqueWords {
		words = append(words, w)
	}
	if len(words) == 0 {
		o.mu.Unlock()
		return errors.ErrEmptyWords
	}
	o.log.Info(fmt.Sprintf("%d censored files loaded [%s]", len(languages), strings.Join(languages, ",")))
	o.log.Info(fmt.Sprintf("%d censored words loaded", len(words)))

	moderator, err := moderation.NewModerator(words, '*')
	if err != nil {
		o.mu.Unlock()
		return err
	}
	moderationWorker := workers.NewModerationWorker(moderator, o.rawEvents, o.domainEvents, o.log)
	o.supervisor.Add(moderationWorker)

	fanoutWorker := workers.NewEventFanout(
		o.log,
		o.domainEvents,
		o.telemetryEvents,
	)
	fanoutWorker.Add(o.sinks)
	o.supervisor.Add(fanoutWorker)

	o.mu.Unlock() // Unlock before blocking on Run

	o.log.Info("Starting orchestrator and all supervised workers")
	o.supervisor.Run(ctx)
	return nil
}

// Stop initiates a graceful shutdown of the orchestrator.
// It cancels the supervision context to signal workers to stop,
// and then closes internal channels to ensure all remaining events are drained.
func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")

	// 1. Cancel the supervised context.
	// This immediately signals all workers to stop blocking on operations.
	o.supervisor.Stop()

	// 2. Close internal domain and telemetry channels.
	// This allows workers to exit their loops when they detect the channel is closed (ok == false),
	// ensuring any buffered events are processed before the worker goroutine terminates.
	if o.domainEvents != nil {
		close(o.domainEvents)
	}
	if o.telemetryEvents != nil {
		close(o.telemetryEvents)
	}
	o.log.Debug("Orchestrator internal channels closed")
}
