package workers

import (
	"chat-lab/domain/analyzer"
	pb "chat-lab/proto/analyzer"
	"context"
	"fmt"
	"log/slog"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type FileSenderWorker struct {
	client   pb.FileAnalyzerServiceClient
	log      *slog.Logger
	fileChan chan *analyzer.FileAnalyzerRequest
}

func NewFileSenderWorker(
	client pb.FileAnalyzerServiceClient,
	log *slog.Logger,
	fileChan chan *analyzer.FileAnalyzerRequest,
) *FileSenderWorker {
	return &FileSenderWorker{
		client:   client,
		log:      log,
		fileChan: fileChan,
	}
}

// Run opens a gRPC stream and listens to the fileChan to forward metadata to the analyzer server.
// It automatically handles conversion from domain models to protobuf messages.
func (w FileSenderWorker) Run(ctx context.Context) error {
	stream, err := w.client.Analyze(ctx)
	if err != nil {
		return fmt.Errorf(" Unable to open the stream to analyze files : %w", err)
	}
	defer w.handleResponse(stream)

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case req, ok := <-w.fileChan:
			if !ok {
				w.log.Info("file scanner directory channel closed")
				return nil
			}
			w.log.Debug("Sending file scann to analyzer", "event", req.Path)
			if err := stream.Send(toPbFileAnalyzerRequest(req)); err != nil {
				w.log.Debug("Failed to send file to analyzer", "event", req.Path, "error", err)
			}
		}
	}
}
func (w FileSenderWorker) handleResponse(stream grpc.ClientStreamingClient[pb.FileAnalyzerRequest, pb.FileAnalyzerResponse]) {
	response, err := stream.CloseAndRecv()
	if err != nil {
		w.log.Info("Error while closing stream", "error", err)
	}
	w.log.Info("File received", "files_received", response.FilesReceived)
	w.log.Info("Bytes processed", "bytes_processed", response.BytesProcessed)
	w.log.Info("Remote file processing terminated at", "ended_at", response.EndedAt.AsTime().Format(time.RFC3339))
}

func toPbFileAnalyzerRequest(req *analyzer.FileAnalyzerRequest) *pb.FileAnalyzerRequest {
	return &pb.FileAnalyzerRequest{
		Path:       req.Path,
		DriveId:    req.DriveID,
		Size:       req.Size,
		Attributes: req.Attributes,
		MimeType:   req.MimeType,
		MagicBytes: req.MagicBytes,
		ScannedAt:  timestamppb.New(req.ScannedAt),
		SourceType: toPbSourceType(req.SourceType),
	}
}

func toPbSourceType(st analyzer.SourceType) pb.SourceType {
	switch st {
	case analyzer.UNSPECIFIED:
		return pb.SourceType_UNSPECIFIED
	case analyzer.LOCALFIXED:
		return pb.SourceType_LOCAL_FIXED
	case analyzer.REMOVABLE:
		return pb.SourceType_REMOVABLE
	case analyzer.NETWORK:
		return pb.SourceType_NETWORK
	default:
		return pb.SourceType_UNSPECIFIED
	}
}
