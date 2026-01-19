package client

import (
	"chat-lab/domain/specialist"
	pb "chat-lab/proto/analysis"
	"context"
	"os"
	"time"
)

type SpecialistClient struct {
	Id         specialist.Metric
	Client     pb.SpecialistServiceClient
	Process    *os.Process
	Port       int
	LastHealth time.Time
}

func NewSpecialistClient(id specialist.Metric,
	client pb.SpecialistServiceClient,
	process *os.Process, port int, lastHealth time.Time,
) *SpecialistClient {
	return &SpecialistClient{
		Id: id, Client: client, Process: process, Port: port,
		LastHealth: lastHealth,
	}
}

// Analyze sends the message content to the specialized binary via gRPC.
// It returns a score-based verdict used for moderation or business statistics.
func (s *SpecialistClient) Analyze(ctx context.Context, request specialist.Request) (specialist.Response, error) {
	response, err := s.Client.Analyze(ctx, &pb.SpecialistRequest{
		MessageId: request.MessageID,
		Content:   request.Content,
		Tags:      request.Tags,
	})
	if err != nil {
		return specialist.Response{}, err
	}
	return specialist.Response{
		Score:            response.Score,
		Label:            response.Label,
		ProcessingTimeMs: int(response.ProcessTimeMs),
		Status:           response.Status,
	}, nil
}
