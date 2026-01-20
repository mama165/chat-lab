package server

import (
	pb "chat-lab/proto/ingestion"
	"chat-lab/services"

	"google.golang.org/grpc"
)

type FileAnalyzerServer struct {
	pb.UnsafeFileAnalyzerServiceServer
	fileAnalyzerService services.IAnalyzerService
}

func NewFileAnalyzerServer(service services.AnalyzerService) *FileAnalyzerServer {
	return &FileAnalyzerServer{
		fileAnalyzerService: service,
	}
}

func (s FileAnalyzerServer) Analyze(stream grpc.ClientStreamingServer[pb.FileAnalyzerRequest, pb.FileAnalyzerResponse]) error {
	err := s.fileAnalyzerService.Process()
	if err != nil {
		return err
	}
	return stream.SendMsg(&pb.FileAnalyzerResponse{})
}
