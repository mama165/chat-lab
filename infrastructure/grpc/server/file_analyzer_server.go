package server

import (
	"chat-lab/domain/analyzer"
	pb "chat-lab/proto/analyzer"
	"chat-lab/services"
	"context"
	"errors"
	"io"
	"log/slog"
	"sync/atomic"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type FileAnalyzerServer struct {
	pb.UnimplementedFileAnalyzerServiceServer
	fileAnalyzerService services.IAnalyzerService
	log                 *slog.Logger
	countAnalyzedFiles  *analyzer.CountAnalyzedFiles
}

func NewFileAnalyzerServer(
	service services.IAnalyzerService,
	log *slog.Logger,
	countAnalyzedFiles *analyzer.CountAnalyzedFiles) *FileAnalyzerServer {
	return &FileAnalyzerServer{
		fileAnalyzerService: service,
		log:                 log,
		countAnalyzedFiles:  countAnalyzedFiles,
	}
}

// Analyze handles a client-side stream of file metadata from a scanner.
// It receives messages in a loop until the client closes the stream (io.EOF),
// then returns a summary including total files received and bytes processed.
// The statistics are read using atomic operations to ensure consistency across multiple concurrent streams.
func (s FileAnalyzerServer) Analyze(stream grpc.ClientStreamingServer[pb.FileAnalyzerRequest, pb.FileAnalyzerResponse]) error {
	for {
		request, err := stream.Recv()
		if err == io.EOF {
			response := pb.FileAnalyzerResponse{
				FilesReceived:  atomic.LoadUint64(&s.countAnalyzedFiles.FilesReceived),
				BytesProcessed: atomic.LoadUint64(&s.countAnalyzedFiles.BytesProcessed),
				EndedAt:        timestamppb.New(time.Now()),
			}
			return stream.SendAndClose(&response)
		}
		err = s.fileAnalyzerService.Analyze(stream.Context(), toRequest(request))
		if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
			s.log.Error("Context has been canceled")
			return err
		}
		if err != nil {
			s.log.Error("Error analyzing file", "error", err)
		}
	}
}

func toRequest(req *pb.FileAnalyzerRequest) analyzer.FileAnalyzerRequest {
	return analyzer.FileAnalyzerRequest{
		Path:       req.Path,
		DriveID:    req.DriveId,
		Size:       req.Size,
		Attributes: req.Attributes,
		MimeType:   req.MimeType,
		MagicBytes: req.MagicBytes,
		ScannedAt:  req.ScannedAt.AsTime(),
		SourceType: toSourceType(req.SourceType),
	}
}

func toSourceType(st pb.SourceType) analyzer.SourceType {
	switch st {
	case pb.SourceType_UNSPECIFIED:
		return analyzer.UNSPECIFIED
	case pb.SourceType_LOCAL_FIXED:
		return analyzer.LOCALFIXED
	case pb.SourceType_REMOVABLE:
		return analyzer.REMOVABLE
	case pb.SourceType_NETWORK:
		return analyzer.NETWORK
	default:
		return analyzer.UNSPECIFIED
	}
}
