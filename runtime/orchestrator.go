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

type Orchestrator struct {
	mu              sync.Mutex
	commands        map[domain.RoomID]chan domain.Command
	sinks           []contract.EventSink
	supervisor      workers.Supervisor
	domainEvents    chan event.DomainEvent
	telemetryEvents chan event.DomainEvent
}

func NewOrchestrator() *Orchestrator {
	return &Orchestrator{
		commands:     make(map[domain.RoomID]chan domain.Command),
		sinks:        nil,
		domainEvents: make(chan event.DomainEvent),
	}
}

func (e *Orchestrator) RegisterRoom(room *domain.Room) {
	e.mu.Lock()
	defer e.mu.Unlock()
	// 1. On crée le canal d'entrée pour les commandes de cette room
	if _, ok := e.commands[room.ID]; ok {
		return
	}
	cmdChan := make(chan domain.Command, 100)
	e.commands[room.ID] = cmdChan

	// 2. On crée le Worker qui encapsule la Room
	worker := workers.NewRoomWorker(room, cmdChan, e.domainEvents, slog.Default())

	// 3. On délègue la vie du worker au supervisor
	e.supervisor.Add(worker)
	e.supervisor.Start(worker)
}

func (e *Orchestrator) RegisterSink(sink contract.EventSink) {
	e.sinks = append(e.sinks, sink)
}

func (e *Orchestrator) Dispatch(cmd domain.Command) {
	e.mu.Lock()
	defer e.mu.Unlock()
	commandChan, ok := e.commands[cmd.RoomID()]
	if !ok {
		return
	}
	select {
	case commandChan <- cmd:
	default:
		// TODO à gérer
	}
}

func (e *Orchestrator) Start(ctx context.Context) {
	fanoutWorker := workers.NewEventFanout(
		slog.Default(),
		e.domainEvents,
		e.telemetryEvents,
	)
	fanoutWorker.Add(e.sinks)
	e.supervisor.Add(fanoutWorker.WithName("event-distributor"))
	e.supervisor.Start(fanoutWorker)
}
