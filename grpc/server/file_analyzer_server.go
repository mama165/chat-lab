package server

import (
	"chat-lab/domain/analyzer"
	pb "chat-lab/proto/analyzer"
	"chat-lab/services"

	"google.golang.org/grpc"
	"google.golang.org/protobuf/types/known/timestamppb"
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
	request, err := stream.Recv()
	response, err := s.fileAnalyzerService.Analyze(toRequest(request))
	if err != nil {
		return err
	}
	return stream.SendMsg(fromResponse(response))
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
		return analyzer.LocalFixed
	case pb.SourceType_REMOVABLE:
		return analyzer.REMOVABLE
	case pb.SourceType_NETWORK:
		return analyzer.NETWORK
	default:
		return analyzer.UNSPECIFIED
	}
}

func fromResponse(resp analyzer.FileAnalyzerResponse) *pb.FileAnalyzerResponse {
	return &pb.FileAnalyzerResponse{
		FilesReceived:  resp.FilesReceived,
		BytesProcessed: resp.BytesProcessed,
		EndedAt:        timestamppb.New(resp.EndedAt),
	}
}
