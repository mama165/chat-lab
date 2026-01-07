// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	"chat-lab/moderation"
	"chat-lab/projection"
	"chat-lab/repositories"
	"chat-lab/repositories/storage"
	"chat-lab/runtime/workers"
	"context"
	"embed"
	"fmt"
	"github.com/samber/lo"
	"log/slog"
	"strings"
	"sync"
	"time"
)

//go:embed censored/*
var censoredFolder embed.FS

type Orchestrator struct {
	mu                sync.Mutex
	log               *slog.Logger
	numWorkers        int
	rooms             map[domain.RoomID]*domain.Room
	permanentSinks    []contract.EventSink
	supervisor        contract.ISupervisor
	registry          contract.IRegistry
	globalCommands    chan domain.Command
	rawEvents         chan event.DomainEvent
	domainEvents      chan event.DomainEvent
	telemetryEvents   chan event.DomainEvent
	messageRepository repositories.Repository
	sinkTimeout       time.Duration
	charReplacement   rune
}

func NewOrchestrator(log *slog.Logger, supervisor *workers.Supervisor,
	registry *Registry, messageRepository repositories.Repository,
	numWorkers, bufferSize int, sinkTimeout time.Duration, charReplacement rune) *Orchestrator {
	return &Orchestrator{
		log:               log,
		numWorkers:        numWorkers,
		rooms:             make(map[domain.RoomID]*domain.Room),
		permanentSinks:    nil,
		supervisor:        supervisor,
		registry:          registry,
		globalCommands:    make(chan domain.Command, bufferSize),
		rawEvents:         make(chan event.DomainEvent, bufferSize),
		domainEvents:      make(chan event.DomainEvent, bufferSize),
		telemetryEvents:   make(chan event.DomainEvent, bufferSize),
		messageRepository: messageRepository,
		sinkTimeout:       sinkTimeout,
		charReplacement:   charReplacement,
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

func (o *Orchestrator) Add(sinks ...contract.EventSink) {
	o.permanentSinks = append(o.permanentSinks, sinks...)
}

func (o *Orchestrator) Dispatch(cmd domain.Command) {
	select {
	case o.globalCommands <- cmd:
	default:
		o.log.Warn(fmt.Sprintf("Global command channel full for Room %d, dropping command", cmd.RoomID()))
	}
}

func (o *Orchestrator) GetMessages(cmd domain.GetMessageCommand) ([]domain.Message, *string, error) {
	messages, cursor, err := o.messageRepository.GetMessages(cmd.Room, cmd.Cursor)
	return fromDiskMessage(messages), cursor, err
}

func fromDiskMessage(messages []repositories.DiskMessage) []domain.Message {
	return lo.Map(messages, func(item repositories.DiskMessage, _ int) domain.Message {
		return domain.Message{
			ID:        item.ID,
			SenderID:  item.Author,
			Content:   item.Content,
			CreatedAt: item.At,
		}
	})
}

func (o *Orchestrator) RegisterParticipant(pID string, roomID domain.RoomID, sink contract.EventSink) {
	o.registry.Subscribe(pID, roomID, sink)
	// Why not send an event UserJoined to notify all members of the room through FanoutWorker
}

// UnregisterParticipant disconnects a user.
func (o *Orchestrator) UnregisterParticipant(pID string, roomID domain.RoomID) {
	o.registry.Unsubscribe(pID, roomID)
}

// Start initiates the orchestrator by preparing all components (workers, moderation, pipeline)
// and then starting the supervisor. It uses a preparation pattern to minimize mutex locking time.
func (o *Orchestrator) Start(ctx context.Context) error {
	// 1. Preparation phase (No Lock)
	// Heavy tasks like I/O (loading files) and CPU (Aho-Corasick build) are done here.
	poolWorkers := o.preparePoolWorkers()

	moderationWorker, err := o.prepareModeration("censored", o.charReplacement)
	if err != nil {
		return err
	}

	fanoutWorker, newSinks := o.preparePipeline()

	// 2. Critical Section (Short Lock)
	// We only lock to update the internal state and the supervisor.
	o.mu.Lock()
	o.permanentSinks = append(o.permanentSinks, newSinks...)

	// Registering all workers to the supervisor
	o.supervisor.Add(moderationWorker)
	o.supervisor.Add(fanoutWorker)
	for _, w := range poolWorkers {
		o.supervisor.Add(w)
	}
	o.mu.Unlock()

	// 3. Execution phase (No Lock)
	o.log.Info("Starting orchestrator and all supervised workers")
	o.supervisor.Run(ctx)
	return nil
}

// preparePoolWorkers creates the basic worker pool for raw command processing.
func (o *Orchestrator) preparePoolWorkers() []contract.Worker {
	var res []contract.Worker
	for i := 0; i < o.numWorkers; i++ {
		res = append(res, workers.NewPoolUnitWorker(o.rooms, o.globalCommands, o.rawEvents, o.log))
	}
	return res
}

// prepareModeration loads censored words and builds the Aho-Corasick automaton.
func (o *Orchestrator) prepareModeration(path string, charReplacement rune) (contract.Worker, error) {
	loader := NewCensoredLoader(censoredFolder)
	data, err := loader.LoadAll("censored")
	if err != nil {
		return nil, err
	}

	o.log.Info(fmt.Sprintf("%d censored files loaded [%s]",
		len(data.Languages), strings.Join(data.Languages, ",")))
	o.log.Info(fmt.Sprintf("%d unique censored words loaded", len(data.Words)))

	moderator, err := moderation.NewModerator(data.Words, charReplacement)
	if err != nil {
		return nil, err
	}

	return workers.NewModerationWorker(moderator, o.rawEvents, o.domainEvents, o.log), nil
}

// preparePipeline initializes the sinks and the fanout worker.
func (o *Orchestrator) preparePipeline() (contract.Worker, []contract.EventSink) {
	// Local sinks that will be added to permanentSinks
	newSinks := []contract.EventSink{
		projection.NewTimeline(),
		storage.NewDiskSink(o.messageRepository, o.log),
	}

	// We prepare the fanout with current permanent sinks + the new ones
	allSinks := append(o.permanentSinks, newSinks...)

	fanoutWorker := workers.NewEventFanout(
		o.log,
		allSinks,
		o.registry,
		o.domainEvents,
		o.telemetryEvents,
		o.sinkTimeout,
	)

	return fanoutWorker, newSinks
}

// Stop initiates a graceful shutdown of the orchestrator.
// It cancels the supervision context to signal workers to stop,
// and then closes internal channels to ensure all remaining events are drained.
func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")

	// 1. Cancel the supervised context.
	// This immediately signals all workers to stop blocking on operations.
	o.supervisor.Stop()

	o.log.Debug("Orchestrator internal channels closed")
}
