//go:generate go run go.uber.org/mock/mockgen -source=contract.go -destination=../mocks/mock_contract.go -package=mocks
package contract

import (
	"chat-lab/domain"
	"chat-lab/domain/chat"
	"context"
	"reflect"
)

type ISupervisor interface {
	Add(worker ...Worker) ISupervisor
	Run(ctx context.Context)
	Start(ctx context.Context, worker Worker)
	Stop()
}

type WorkerName string

// Worker doesn't protect itself
// Can be silly, focused
type Worker interface {
	Run(ctx context.Context) error
}

// GetWorkerName uses reflection to retrieve the type name of the worker.
// This is used for logging and supervision purposes during worker initialization
// or lifecycle events, avoiding the need for manual naming in the Worker interface.
func GetWorkerName(w Worker) string {
	if w == nil {
		return "NilWorker"
	}
	t := reflect.TypeOf(w)
	for t.Kind() == reflect.Ptr {
		t = t.Elem()
	}
	return t.Name()
}

type FileAnalyzerEvent interface {
	Namespace() string
}

type DomainEvent interface {
	RoomID() chat.RoomID
}

type EventSink[E any] interface {
	Consume(ctx context.Context, e E) error
}
type IRegistry interface {
	GetSinksForRoom(roomID chat.RoomID) []EventSink[DomainEvent]
	Subscribe(participantID string, roomID chat.RoomID, sink EventSink[DomainEvent])
	Unsubscribe(participantID string, roomID chat.RoomID)
}

type IOrchestrator interface {
	RegisterRoom(room *chat.Room)
	PostMessage(ctx context.Context, cmd chat.PostMessageCommand) error
	GetMessages(cmd chat.GetMessageCommand) ([]chat.Message, *string, error)
	RegisterParticipant(pID string, roomID chat.RoomID, sink EventSink[DomainEvent])
	UnregisterParticipant(pID string, roomID chat.RoomID)
	Start(ctx context.Context) error
	Stop()
}

// SpecialistCoordinator defines the ability to fan-out analysis requests
// to all available sidecars.
type SpecialistCoordinator interface {
	Broadcast(ctx context.Context, req domain.SpecialistRequest) error
}

// ISpecialistClient defines the contract for interacting with specialized
// analysis processes (Toxicity, Sentiment, Business).
// This abstraction allows the Orchestrator to remain agnostic of the
// underlying communication protocol (gRPC, HTTP, or local).
type ISpecialistClient interface {
	Analyze(ctx context.Context, request domain.Request) (domain.Response, error)
}

type IFileAccumulator interface {
	ProcessResponse(ctx context.Context, resp domain.FileDownloaderResponse) error
}
