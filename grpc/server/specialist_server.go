package server

import (
	pb "chat-lab/proto/analysis"
	"context"
	"fmt"
	"log/slog"
	"time"
)

type SpecialistServer struct {
	pb.UnimplementedSpecialistServiceServer
	Id   string
	Kind string
	Log  *slog.Logger
}

func NewSpecialistServer(id string, kind string, log *slog.Logger) *SpecialistServer {
	return &SpecialistServer{Id: id, Kind: kind, Log: log}
}

// Analyze implements the gRPC interface defined in your proto
func (s *SpecialistServer) Analyze(ctx context.Context, req *pb.SpecialistRequest) (*pb.SpecialistResponse, error) {
	// Here we simulate processing time (Topic 4: Lead Time)
	start := time.Now()

	// Mock logic: in the future, this is where Bluge or an AI model will work
	label := fmt.Sprintf("processed_by_%s", s.Kind)

	s.Log.Info("Specialist called", s.Kind, label)

	return &pb.SpecialistResponse{
		Score:         0.95,
		Label:         label,
		ProcessTimeMs: time.Since(start).Milliseconds(),
		Status:        "OK",
	}, nil
}
