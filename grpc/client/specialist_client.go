package client

import (
	"chat-lab/domain"
	pb "chat-lab/proto/analysis"
	"context"

	"google.golang.org/grpc"
)

type SpecialistClient struct {
	Id     domain.SpecialistID
	client pb.SpecialistServiceClient
}

type SpecialistGrpcClient struct {
	address string
}

func NewSpecialistClient(id domain.SpecialistID, conn *grpc.ClientConn) *SpecialistClient {
	client := pb.NewSpecialistServiceClient(conn)
	return &SpecialistClient{Id: id, client: client}
}

// Analyze sends the message content to the specialized binary via gRPC.
// It returns a score-based verdict used for moderation or business statistics.
func (s *SpecialistClient) Analyze(ctx context.Context, request domain.SpecialistRequest) (domain.SpecialistResponse, error) {
	response, err := s.client.Analyze(ctx, &pb.SpecialistRequest{
		MessageId: request.MessageID,
		Content:   request.Content,
		Tags:      request.Tags,
	})
	if err != nil {
		return domain.SpecialistResponse{}, err
	}
	return domain.SpecialistResponse{
		Score:            response.Score,
		Label:            response.Label,
		ProcessingTimeMs: int(response.ProcessTimeMs),
		Status:           response.Status,
	}, nil
}
