// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/runtime/workers"
	"time"
)

type EventSink interface {
	Consume(event event.DomainEvent)
}

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
	commands map[domain.RoomID]chan domain.Command
	sinks    []EventSink
	workers.Supervisor
}

func NewOrchestrator() *Orchestrator {
	return &Orchestrator{
		commands: make(map[domain.RoomID]chan domain.Command),
		sinks:    nil,
	}
}

func (e *Orchestrator) RegisterRoom(room *domain.Room) {
	// 1. On crée le canal d'entrée pour les commandes de cette room
	cmdChan := make(chan domain.Command, 100)
	e.commands[room.ID] = cmdChan

	// 2. On crée le Worker qui encapsule la Room
	worker := runtime.NewRoomWorker(room, cmdChan, e.eventBus)

	// 3. On délègue la vie du worker au supervisor
	e.supervisor.Add(worker)
	e.supervisor.Start(worker)
}

func (e *Orchestrator) RegisterSink(sink EventSink) {
	e.sinks = append(e.sinks, sink)
}

func (e *Orchestrator) Dispatch(cmd domain.Command) {
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
