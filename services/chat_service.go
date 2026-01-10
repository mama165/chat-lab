package services

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/runtime"
	"context"
)

type IChatService interface {
	PostMessage(ctx context.Context, cmd domain.PostMessageCommand) error
	GetMessages(cmd domain.GetMessageCommand) ([]domain.Message, *string, error)
	JoinRoom(userID string, roomID domain.RoomID, sink contract.EventSink)
	LeaveRoom(userID string, roomID domain.RoomID)
}

type ChatService struct {
	orchestrator *runtime.Orchestrator
}

func NewChatService(o *runtime.Orchestrator) *ChatService {
	return &ChatService{orchestrator: o}
}

func (s *ChatService) PostMessage(ctx context.Context, cmd domain.PostMessageCommand) error {
	return s.orchestrator.PostMessage(ctx, cmd)
}

func (s *ChatService) GetMessages(cmd domain.GetMessageCommand) ([]domain.Message, *string, error) {
	return s.orchestrator.GetMessages(cmd)
}

func (s *ChatService) JoinRoom(userID string, roomID domain.RoomID, sink contract.EventSink) {
	room := domain.NewRoom(roomID)
	s.orchestrator.RegisterRoom(room)
	s.orchestrator.RegisterParticipant(userID, domain.RoomID(roomID), sink)
}

func (s *ChatService) LeaveRoom(userID string, roomID domain.RoomID) {
	s.orchestrator.UnregisterParticipant(userID, roomID)
}
