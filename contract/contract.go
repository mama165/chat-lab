//go:generate go run go.uber.org/mock/mockgen -source=contract.go -destination=../mocks/mock_contract.go -package=mocks
package contract

import (
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"chat-lab/domain/specialist"
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

type EventSink interface {
	Consume(ctx context.Context, e event.DomainEvent) error
}
type IRegistry interface {
	GetSinksForRoom(roomID chat.RoomID) []EventSink
	Subscribe(participantID string, roomID chat.RoomID, sink EventSink)
	Unsubscribe(participantID string, roomID chat.RoomID)
}

type IOrchestrator interface {
	RegisterRoom(room *chat.Room)
	PostMessage(ctx context.Context, cmd chat.PostMessageCommand) error
	GetMessages(cmd chat.GetMessageCommand) ([]chat.Message, *string, error)
	RegisterParticipant(pID string, roomID chat.RoomID, sink EventSink)
	UnregisterParticipant(pID string, roomID chat.RoomID)
	Start(ctx context.Context) error
	Stop()
}

type IAnalyzer interface {
	AnalyzeAll(ctx context.Context, messageID string, content string) map[specialist.Metric]specialist.Response
}

// ISpecialistClient defines the contract for interacting with specialized
// analysis processes (Toxicity, Sentiment, Business).
// This abstraction allows the Orchestrator to remain agnostic of the
// underlying communication protocol (gRPC, HTTP, or local).
type ISpecialistClient interface {
	Analyze(ctx context.Context, request specialist.Request) (specialist.Response, error)
}
