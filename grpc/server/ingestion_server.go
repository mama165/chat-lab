package server

import (
	pb "chat-lab/proto/ingestion"

	"google.golang.org/grpc"
)

type IngestionServer struct {
	pb.UnimplementedIngestionServiceServer
}

func NewIngestionServer() *IngestionServer {
	return &IngestionServer{}
}

func (s IngestionServer) IngestFile(stream grpc.ClientStreamingServer[pb.FileIngestRequest, pb.FileSummaryResponse]) error {

	return stream.SendMsg(&pb.FileSummaryResponse{})
}
