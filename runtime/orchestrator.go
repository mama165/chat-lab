// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/runtime/workers"
	"context"
	"log/slog"
	"sync"
	"time"
)

type AsyncSink struct {
	name   string
	inbox  chan event.DomainEvent
	delay  time.Duration
	handle func(domainEvent event.DomainEvent)
}

func (a AsyncSink) Consume(event event.DomainEvent) {
	select {
	case a.inbox <- event:
	}
	//TODO implement me
	panic("implement me")
}

// Internal structure to keep track of a room and its resources
type roomEntry struct {
	room    *domain.Room
	command chan domain.Command
}

type Orchestrator struct {
	mu              sync.Mutex
	log             *slog.Logger
	rooms           map[domain.RoomID]roomEntry
	sinks           []contract.EventSink
	supervisor      contract.ISupervisor
	domainEvents    chan event.DomainEvent
	telemetryEvents chan event.DomainEvent
}

func NewOrchestrator(log *slog.Logger, bufferSize int) *Orchestrator {
	return &Orchestrator{
		log:             log,
		rooms:           make(map[domain.RoomID]roomEntry),
		sinks:           nil,
		supervisor:      workers.NewSupervisor(&sync.WaitGroup{}, log),
		domainEvents:    make(chan event.DomainEvent, bufferSize),
		telemetryEvents: make(chan event.DomainEvent, bufferSize),
	}
}

// RegisterRoom creates a dedicated worker and command channel for a room.
func (e *Orchestrator) RegisterRoom(room *domain.Room) {
	e.mu.Lock()
	defer e.mu.Unlock()
	if _, ok := e.rooms[room.ID]; ok {
		return // Room already exists, do nothing
	}
	cmdChan := make(chan domain.Command, 100)
	e.rooms[room.ID] = roomEntry{room: room, command: cmdChan}
}

func (e *Orchestrator) RegisterSink(sink contract.EventSink) {
	e.sinks = append(e.sinks, sink)
}

func (e *Orchestrator) Dispatch(cmd domain.Command) {
	e.mu.Lock()
	defer e.mu.Unlock()
	entry, ok := e.rooms[cmd.RoomID()]
	if !ok {
		return
	}
	select {
	case entry.command <- cmd:
	default:
		// TODO à gérer
	}
}

func (e *Orchestrator) Start(ctx context.Context) {
	e.mu.Lock()

	for _, entry := range e.rooms {
		worker := workers.NewRoomWorker(entry.room, entry.command, e.domainEvents, e.log)
		e.supervisor.Add(worker)
	}

	fanoutWorker := workers.NewEventFanout(
		e.log,
		e.domainEvents,
		e.telemetryEvents,
	)
	fanoutWorker.Add(e.sinks)
	e.supervisor.Add(fanoutWorker.WithName("fanout-worker"))

	e.mu.Unlock() // Unlock before blocking on Run

	e.log.Info("Starting orchestrator and all supervised workers")
	e.supervisor.Run(ctx)
}

func (e *Orchestrator) Stop() {
	e.log.Info("Requesting orchestrator shutdown")
	e.supervisor.Stop()
}
