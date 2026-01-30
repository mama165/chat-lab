//go:generate go run go.uber.org/mock/mockgen -source=file_analyzer.go -destination=../../../mocks/mock_file_analyzer.go -package=mocks

package client

import (
	pb "chat-lab/proto/analyzer"
	"context"
	"google.golang.org/grpc"
)

// FileAnalyzerClient Define a clean interface for the analyzer service to
// facilitate mocking and decouple from gRPC generated code.
type FileAnalyzerClient interface {
	Analyze(ctx context.Context) (FileAnalyzerStream, error)
}

type FileAnalyzerStream interface {
	Send(*pb.FileAnalyzerRequest) error
	CloseAndRecv() (*pb.FileAnalyzerResponse, error)
	CloseSend() error
}

type gRPCAnalyzerClient struct {
	client pb.FileAnalyzerServiceClient
}

// Analyze Call the generated gRPC client.
func (g *gRPCAnalyzerClient) Analyze(ctx context.Context) (FileAnalyzerStream, error) {
	return g.client.Analyze(ctx)
}

func NewFileAnalyzerClient(cc grpc.ClientConnInterface) FileAnalyzerClient {
	return &gRPCAnalyzerClient{client: pb.NewFileAnalyzerServiceClient(cc)}
}
