package services

import (
	"chat-lab/contract"
	"chat-lab/domain/chat"
	"chat-lab/runtime"
	"context"
)

type IChatService interface {
	PostMessage(ctx context.Context, cmd chat.PostMessageCommand) error
	GetMessages(cmd chat.GetMessageCommand) ([]chat.Message, *string, error)
	JoinRoom(userID string, roomID chat.RoomID, sink contract.EventSink[contract.DomainEvent])
	LeaveRoom(userID string, roomID chat.RoomID)
}

type ChatService struct {
	orchestrator *runtime.Orchestrator
}

func NewChatService(o *runtime.Orchestrator) *ChatService {
	return &ChatService{orchestrator: o}
}

func (s *ChatService) PostMessage(ctx context.Context, cmd chat.PostMessageCommand) error {
	return s.orchestrator.PostMessage(ctx, cmd)
}

func (s *ChatService) GetMessages(cmd chat.GetMessageCommand) ([]chat.Message, *string, error) {
	return s.orchestrator.GetMessages(cmd)
}

func (s *ChatService) JoinRoom(userID string, roomID chat.RoomID, sink contract.EventSink[contract.DomainEvent]) {
	room := chat.NewRoom(roomID)
	s.orchestrator.RegisterRoom(room)
	s.orchestrator.RegisterParticipant(userID, roomID, sink)
}

func (s *ChatService) LeaveRoom(userID string, roomID chat.RoomID) {
	s.orchestrator.UnregisterParticipant(userID, roomID)
}
