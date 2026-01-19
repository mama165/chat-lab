package server

import (
	"chat-lab/domain/chat"
	"chat-lab/domain/event"
	"chat-lab/errors"
	pb "chat-lab/proto/chat"
	"chat-lab/services"
	"chat-lab/sink"
	"context"
	"fmt"
	"log/slog"
	"time"

	"github.com/samber/lo"
	"google.golang.org/protobuf/types/known/timestamppb"

	"github.com/google/uuid"
)

type ChatServer struct {
	pb.UnimplementedChatServiceServer
	chatService          services.IChatService
	connectionBufferSize int
	log                  *slog.Logger
	deliveryTimeout      time.Duration
}

func NewChatServer(log *slog.Logger, chatService services.IChatService,
	connectionBufferSize int, deliveryTimeout time.Duration) *ChatServer {
	return &ChatServer{chatService: chatService,
		connectionBufferSize: connectionBufferSize, log: log,
		deliveryTimeout: deliveryTimeout,
	}
}

func (s *ChatServer) GetMessage(_ context.Context, req *pb.GetMessageRequest) (*pb.GetMessageResponse, error) {
	messages, cursor, err := s.chatService.GetMessages(chat.GetMessageCommand{
		Room:   int(req.Room),
		Cursor: req.Cursor,
	})
	return &pb.GetMessageResponse{
		MessageResponse: toMessageResponse(messages),
		Cursor:          cursor,
	}, err
}

func toMessageResponse(messages []chat.Message) []*pb.MessageResponse {
	return lo.Map(messages, func(item chat.Message, _ int) *pb.MessageResponse {
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
	command := chat.PostMessageCommand{
		Room:      int(req.RoomId),
		UserID:    userID,
		Content:   req.Content,
		CreatedAt: time.Now().UTC(),
	}
	if err := s.chatService.PostMessage(ctx, command); err != nil {
		return nil, errors.MapToGRPCError(err)
	}
	return &pb.PostMessageResponse{Success: true}, nil
}

// Connect establishes a long-lived bidirectional-like stream for real-time delivery.
// It registers a dedicated gRPC Sink in the Orchestrator's registry.
// This method blocks until the client disconnects or a network error occurs.
// Proper cleanup is ensured via deferred unregistration to prevent memory leaks in the registry.
func (s *ChatServer) Connect(req *pb.ConnectRequest, stream pb.ChatService_ConnectServer) error {
	sink := sink.NewGrpcSink(s.log, s.connectionBufferSize, s.deliveryTimeout)
	userID := uuid.NewString() // TODO To be extracted from metadata
	roomID := chat.RoomID(req.RoomId)
	s.chatService.JoinRoom(userID, roomID, sink)
	defer s.chatService.LeaveRoom(userID, roomID)

	for {
		select {
		case <-stream.Context().Done():
			s.log.Warn(fmt.Sprintf("Client %s disconnected from %d", userID, roomID))
			return nil
		case evt := <-sink.ConnectedUserEvent:
			switch e := evt.(type) {
			case event.SanitizedMessage:
				if err := stream.Send(lo.ToPtr(toChatEvent(e))); err != nil {
					s.log.Error("failed to push event to stream",
						"user_id", userID,
						"room_id", roomID,
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
		Content:   e.Content,
		CreatedAt: timestamppb.New(e.At),
	}
	return pb.ChatEvent{
		Event: &pb.ChatEvent_Message{
			Message: &messageEvent,
		},
	}
}
