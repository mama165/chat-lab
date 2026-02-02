// Package runtime handles event production, propagation, gossip, and quiet periods.
// It orchestrates the system without containing business logic or domain rules.
package runtime

import (
	"chat-lab/ai"
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"chat-lab/errors"
	"chat-lab/infrastructure/storage"
	"chat-lab/moderation"
	"chat-lab/runtime/workers"
	"chat-lab/sink"
	"context"
	"embed"
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"

	"github.com/samber/lo"
)

//go:embed censored/*
var censoredFolder embed.FS

type Orchestrator struct {
	mu                   sync.Mutex
	log                  *slog.Logger
	counter              *event.Counter
	numWorkers           int
	rooms                map[chat.RoomID]*chat.Room
	supervisor           contract.ISupervisor
	registry             contract.IRegistry
	globalCommands       chan chat.Command
	moderationChan       chan event.Event
	eventChan            chan event.Event
	telemetryChan        chan event.Event
	fileRequestChan      chan<- domain.FileDownloaderRequest
	messageRepository    storage.IMessageRepository
	analysisRepository   storage.IAnalysisRepository
	fileTaskRepository   storage.IFileTaskRepository
	coordinator          contract.SpecialistCoordinator
	sinkTimeout          time.Duration
	bufferTimeout        time.Duration
	specialistTimeout    time.Duration
	metricInterval       time.Duration
	latencyThreshold     time.Duration
	waitAndFail          time.Duration
	charReplacement      rune
	lowCapacityThreshold int
	maxContentLength     int
	minScoring           float64
	maxScoring           float64
	maxAnalyzedEvent     int
	fileTransferInterval time.Duration
	pendingFileBatchSize int
}

func NewOrchestrator(log *slog.Logger, supervisor *workers.Supervisor,
	registry *Registry, telemetryChan, eventChan chan event.Event,
	fileRequestChan chan<- domain.FileDownloaderRequest,
	messageRepository storage.IMessageRepository,
	analysisRepository storage.IAnalysisRepository,
	fileTaskRepository storage.IFileTaskRepository,
	specialistCoordinator contract.SpecialistCoordinator,
	numWorkers, bufferSize int,
	sinkTimeout, bufferTimeout, specialistTimeout time.Duration,
	metricInterval, latencyThreshold, waitAndFail time.Duration, charReplacement rune,
	lowCapacityThreshold, maxContentLength int,
	minScoring, maxScoring float64, maxAnalyzedEvent int,
	fileTransferInterval time.Duration, pendingFileBatchSize int,
) *Orchestrator {
	return &Orchestrator{
		log:                  log,
		counter:              event.NewCounter(),
		numWorkers:           numWorkers,
		rooms:                make(map[chat.RoomID]*chat.Room),
		supervisor:           supervisor,
		registry:             registry,
		telemetryChan:        telemetryChan,
		fileRequestChan:      fileRequestChan,
		globalCommands:       make(chan chat.Command, bufferSize),
		moderationChan:       make(chan event.Event, bufferSize),
		eventChan:            eventChan,
		messageRepository:    messageRepository,
		analysisRepository:   analysisRepository,
		fileTaskRepository:   fileTaskRepository,
		coordinator:          specialistCoordinator,
		sinkTimeout:          sinkTimeout,
		bufferTimeout:        bufferTimeout,
		specialistTimeout:    specialistTimeout,
		metricInterval:       metricInterval,
		latencyThreshold:     latencyThreshold,
		waitAndFail:          waitAndFail,
		charReplacement:      charReplacement,
		lowCapacityThreshold: lowCapacityThreshold,
		maxContentLength:     maxContentLength,
		minScoring:           minScoring,
		maxScoring:           maxScoring,
		maxAnalyzedEvent:     maxAnalyzedEvent,
		fileTransferInterval: fileTransferInterval,
		pendingFileBatchSize: pendingFileBatchSize,
	}
}

// RegisterRoom creates a dedicated command channel for a Room.
func (o *Orchestrator) RegisterRoom(room *chat.Room) {
	o.mu.Lock()
	defer o.mu.Unlock()
	if _, ok := o.rooms[room.ID]; ok {
		o.log.Info(fmt.Sprintf("Room %d already exists", room.ID))
		return
	}
	o.rooms[room.ID] = room
}

// PostMessage attempts to buffer a command using a "Wait-and-Fail" backpressure strategy.
// It allows for a short grace period (o.waitAndFail) to absorb traffic bursts
// before rejecting the request with errors.ErrServerOverloaded to protect system stability.
func (o *Orchestrator) PostMessage(ctx context.Context, cmd chat.PostMessageCommand) error {
	if len(cmd.Content) > o.maxContentLength {
		return errors.ErrContentTooLarge
	}
	timer := time.NewTimer(o.waitAndFail)
	defer timer.Stop()

	select {
	case <-ctx.Done():
		return ctx.Err()
	case o.globalCommands <- cmd:
		return nil
	case <-timer.C:
		o.log.Warn("backpressure: system too busy to accept message",
			"room_id", cmd.RoomID())
		return errors.ErrServerOverloaded
	}
}

func (o *Orchestrator) GetMessages(cmd chat.GetMessageCommand) ([]chat.Message, *string, error) {
	messages, cursor, err := o.messageRepository.GetMessages(cmd.Room, cmd.Cursor)
	return fromDiskMessage(messages), cursor, err
}

func fromDiskMessage(messages []storage.DiskMessage) []chat.Message {
	return lo.Map(messages, func(item storage.DiskMessage, _ int) chat.Message {
		return chat.Message{
			ID:        item.ID,
			SenderID:  item.Author,
			Content:   item.Content,
			CreatedAt: item.At,
		}
	})
}

func (o *Orchestrator) RegisterParticipant(pID string, roomID chat.RoomID,
	sink contract.EventSink[contract.DomainEvent]) {
	o.registry.Subscribe(pID, roomID, sink)
	// Why not send an event UserJoined to notify all members of the room through FanoutWorker
}

// UnregisterParticipant disconnects a user.
func (o *Orchestrator) UnregisterParticipant(pID string, roomID chat.RoomID) {
	o.registry.Unsubscribe(pID, roomID)
}

// Start initiates the orchestrator by preparing all components (workers, moderation, pipeline)
// and then starting the supervisor. It uses a preparation pattern to minimize mutex locking time.
func (o *Orchestrator) Start(ctx context.Context) error {
	// 1. Preparation phase (No Lock)
	// Heavy tasks like I/O (loading files) and CPU (Aho-Corasick build) are done here.
	poolWorkers := o.preparePoolWorkers()

	fileTransferWorker := o.prepareFileTransferWorker()

	moderationWorker, err := o.prepareModeration("censored", o.charReplacement)
	if err != nil {
		return err
	}

	fanoutWorkers := o.PrepareFanouts()
	channelCapWorker, telemetryWorker := o.prepareTelemetry()

	// 2. Critical Section (Short Lock)
	// We only lock to update the internal state and the supervisor.
	o.mu.Lock()

	// Registering all workers to the supervisor
	o.supervisor.Add(
		moderationWorker,
		channelCapWorker, telemetryWorker,
	)
	o.supervisor.Add(fanoutWorkers...)
	o.supervisor.Add(poolWorkers...)
	o.supervisor.Add(fileTransferWorker)

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
		res = append(res, workers.NewPoolUnitWorker(o.rooms, o.globalCommands, o.moderationChan, o.log))
	}
	return res
}

func (o *Orchestrator) prepareFileTransferWorker() contract.Worker {
	return workers.NewFileTransferWorker(
		o.fileRequestChan,
		o.fileTaskRepository,
		o.log,
		o.fileTransferInterval,
		o.pendingFileBatchSize,
	)
}

// prepareModeration loads censored words and builds the Aho-Corasick automaton.
func (o *Orchestrator) prepareModeration(path string, charReplacement rune) (contract.Worker, error) {
	loader := NewCensoredLoader(censoredFolder)
	data, err := loader.LoadAll(path)
	if err != nil {
		return nil, err
	}

	o.log.Info(fmt.Sprintf("%d censored files loaded [%s]",
		len(data.Languages), strings.Join(data.Languages, ",")))
	o.log.Info(fmt.Sprintf("%d unique censored words loaded", len(data.Words)))

	moderator, err := moderation.NewModerator(data.Words, charReplacement, o.log)
	if err != nil {
		return nil, err
	}

	o.log.Info("Loading AI analyzer", "vector size", ai.VectorSize)
	o.log.Info("AI ambiguous scoring between", "min", o.minScoring, "max", o.maxScoring)

	return workers.NewModerationWorker(moderator, o.coordinator, o.moderationChan, o.eventChan, o.log), nil
}

// PrepareFanouts initializes the sinks and the fanout workers.
// Fanout worker handles a lot of events
func (o *Orchestrator) PrepareFanouts() []contract.Worker {
	diskSink := sink.NewDiskSink(o.messageRepository, o.log)
	analysisSink := sink.NewAnalysisSink(
		o.analysisRepository, o.fileTaskRepository, o.log,
		o.maxAnalyzedEvent, o.bufferTimeout, o.specialistTimeout)

	var res []contract.Worker
	for i := 0; i < o.numWorkers; i++ {
		res = append(res, workers.NewEventFanoutWorker(
			o.log,
			diskSink,
			analysisSink,
			o.registry,
			o.eventChan,
			o.telemetryChan,
			o.sinkTimeout,
		))
	}
	return res
}

func (o *Orchestrator) prepareTelemetry() (contract.Worker, contract.Worker) {
	handlers := []event.Handler{
		event.NewChannelCapacityHandler(o.log, o.lowCapacityThreshold),
		event.NewCensoredHandler(o.log, o.counter),
		event.NewLatencyHandler(o.log, o.latencyThreshold),
		event.NewMessageSentHandler(o.log, o.counter),
		event.NewWorkerRestartedAfterPanicHandler(o.log, o.counter),
	}
	channels := []workers.NamedChannel{
		{Name: "EventChan", Channel: o.eventChan},
		{Name: "ModerationChan", Channel: o.moderationChan},
		{Name: "TelemetryChan", Channel: o.telemetryChan},
		{Name: "FileRequestChan", Channel: o.fileRequestChan},
		{Name: "GlobalCommands", Channel: o.globalCommands},
	}
	channelCapWorker := workers.NewChannelCapacityWorker(
		o.log, channels, o.telemetryChan,
		o.metricInterval,
	)
	telemetryWorker := workers.NewTelemetryWorker(o.log, o.metricInterval, o.telemetryChan, handlers)

	return channelCapWorker, telemetryWorker
}

// Stop initiates a graceful shutdown of the orchestrator.
// It cancels the supervision context to signal workers to stop,
// and then closes internal channels to ensure all remaining events are drained.
func (o *Orchestrator) Stop() {
	o.log.Info("Requesting orchestrator shutdown")

	// 1. Cancel the supervised context.
	// This immediately signals all workers to stop blocking on operations.
	o.supervisor.Stop()

	close(o.globalCommands)
	close(o.moderationChan)
	close(o.eventChan)

	o.log.Debug("Orchestrator internal channels closed")
}
