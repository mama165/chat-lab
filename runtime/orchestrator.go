// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/runtime/workers"
	"context"
	"fmt"
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
func (o *Orchestrator) RegisterRoom(room *domain.Room) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, ok := o.rooms[room.ID]; ok {
		o.log.Info(fmt.Sprintf("Room %d already exists", room.ID))
		return // Room already exists, do nothing
	}
	cmdChan := make(chan domain.Command, 100)
	o.rooms[room.ID] = roomEntry{room: room, command: cmdChan}
}

func (o *Orchestrator) RegisterSink(sink contract.EventSink) {
	o.sinks = append(o.sinks, sink)
}

func (o *Orchestrator) Dispatch(cmd domain.Command) {
	o.mu.Lock()
	defer o.mu.Unlock()
	entry, ok := o.rooms[cmd.RoomID()]
	if !ok {
		o.log.Info(fmt.Sprintf("Room %d doesn't exists", cmd.RoomID()))
		return
	}
	select {
	case entry.command <- cmd:
	default:
		// TODO à gérer
	}
}

func (o *Orchestrator) Start(ctx context.Context) {
	o.mu.Lock()

	for _, entry := range o.rooms {
		worker := workers.NewRoomWorker(entry.room, entry.command, o.domainEvents, o.log)
		o.supervisor.Add(worker)
	}

	fanoutWorker := workers.NewEventFanout(
		o.log,
		o.domainEvents,
		o.telemetryEvents,
	)
	fanoutWorker.Add(o.sinks)
	o.supervisor.Add(fanoutWorker.WithName("fanout-worker"))

	o.mu.Unlock() // Unlock before blocking on Run

	o.log.Info("Starting orchestrator and all supervised workers")
	o.supervisor.Run(ctx)
}

func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")
	o.supervisor.Stop()
}
