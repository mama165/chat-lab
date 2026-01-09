package grpc

import (
	"chat-lab/contract"
	"chat-lab/domain"
	"chat-lab/domain/event"
	pb "chat-lab/proto/chat"
	"chat-lab/runtime"
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/samber/lo"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/google/uuid"
)

type ChatServer struct {
	pb.UnimplementedChatServiceServer
	orchestrator         contract.IOrchestrator
	connectionBufferSize int
	log                  *slog.Logger
	deliveryTimeout      time.Duration
}

func NewChatServer(log *slog.Logger, orchestrator *runtime.Orchestrator,
	connectionBufferSize int, deliveryTimeout time.Duration) *ChatServer {
	return &ChatServer{orchestrator: orchestrator,
		connectionBufferSize: connectionBufferSize, log: log,
		deliveryTimeout: deliveryTimeout,
	}
}

func (s *ChatServer) GetMessage(_ context.Context, req *pb.GetMessageRequest) (*pb.GetMessageResponse, error) {
	messages, cursor, err := s.orchestrator.GetMessages(domain.GetMessageCommand{
		Room:   int(req.Room),
		Cursor: req.Cursor,
	})
	return &pb.GetMessageResponse{
		MessageResponse: toMessageResponse(messages),
		Cursor:          cursor,
	}, err
}

func toMessageResponse(messages []domain.Message) []*pb.MessageResponse {
	return lo.Map(messages, func(item domain.Message, _ int) *pb.MessageResponse {
		return &pb.MessageResponse{
			MessageId: item.ID.String(),
			Author:    item.SenderID,
			Content:   item.Content,
			CreatedAt: timestamppb.New(item.CreatedAt),
		}
	})
}

// PostMessage handles an incoming message sending intent.
// It follows an asynchronous pattern: the message is dispatched to the engine
// but not immediately broadcast back to the sender in this call.
// The sender will receive its own message via the 'Connect' stream like any other participant,
// ensuring a single source of truth for message order, timestamps, and sanitization.
func (s *ChatServer) PostMessage(ctx context.Context, req *pb.PostMessageRequest) (*pb.PostMessageResponse, error) {
	userID := uuid.NewString() // TODO To be extracted from metadata
	command := domain.PostMessageCommand{
		Room:      int(req.RoomId),
		UserID:    userID,
		Content:   req.Content,
		CreatedAt: time.Now().UTC(),
	}
	if err := s.orchestrator.PostMessage(ctx, command); err != nil {
		return nil, status.Error(codes.ResourceExhausted, "too many messages, please backoff")
	}
	return &pb.PostMessageResponse{Success: true}, nil
}

// Connect establishes a long-lived bidirectional-like stream for real-time delivery.
// It registers a dedicated gRPC Sink in the Orchestrator's registry.
// This method blocks until the client disconnects or a network error occurs.
// Proper cleanup is ensured via deferred unregistration to prevent memory leaks in the registry.
func (s *ChatServer) Connect(req *pb.ConnectRequest, stream pb.ChatService_ConnectServer) error {
	sink := NewGrpcSink(s.log, s.connectionBufferSize, s.deliveryTimeout)
	userID := uuid.NewString() // TODO To be extracted from metadata
	room := domain.Room{ID: domain.RoomID(req.RoomId)}
	s.orchestrator.RegisterRoom(&room)
	s.orchestrator.RegisterParticipant(userID, domain.RoomID(req.RoomId), sink)
	defer s.orchestrator.UnregisterParticipant(userID, room.ID)

	for {
		select {
		case <-stream.Context().Done():
			s.log.Warn(fmt.Sprintf("Client %s disconnected from %d", userID, room.ID))
			return nil
		case evt := <-sink.connectedUserEvent:
			switch e := evt.(type) {
			case event.SanitizedMessage:
				if err := stream.Send(lo.ToPtr(toChatEvent(e))); err != nil {
					s.log.Error("failed to push event to stream",
						"user_id", userID,
						"room_id", room.ID,
						"error", err)
					return err
				}
			}
		}
	}
}

func toChatEvent(e event.SanitizedMessage) pb.ChatEvent {
	messageEvent := pb.MessageEvent{
		MessageId: e.ID.String(),
		Author:    e.Author,
		Content:   e.SanitizedContent,
		CreatedAt: timestamppb.New(e.At),
	}
	return pb.ChatEvent{
		Event: &pb.ChatEvent_Message{
			Message: &messageEvent,
		},
	}
}
