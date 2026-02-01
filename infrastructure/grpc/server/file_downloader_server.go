package server

import (
	"chat-lab/domain"
	pb "chat-lab/proto/analyzer"
	"google.golang.org/grpc"
	"log/slog"
)

type FileDownloaderServer struct {
	pb.UnimplementedFileDownloaderServiceServer
	log          *slog.Logger
	requestChan  chan domain.FileDownloaderRequest
	responseChan chan domain.FileDownloaderResponse
}

func NewFileDownloaderServer(
	log *slog.Logger,
	requestChan chan domain.FileDownloaderRequest,
	responseChan chan domain.FileDownloaderResponse) *FileDownloaderServer {
	return &FileDownloaderServer{
		log:          log,
		requestChan:  requestChan,
		responseChan: responseChan,
	}
}

// Download implements the bidirectional streaming logic for file transfers.
// It decouples reading from writing by using two dedicated goroutines:
// 1. A receiver loop that pushes incoming requests into the service's processing pipeline.
// 2. A sender loop that streams processed chunks, metadata, or signatures back to the Master.
// This concurrent approach ensures maximum throughput, prevents head-of-line blocking,
// and allows the 20 workers to push data back as soon as it's ready, regardless of new incoming requests.
func (s *FileDownloaderServer) Download(stream grpc.BidiStreamingServer[pb.FileDownloaderRequest, pb.FileDownloaderResponse]) error {
	errChan := make(chan error, 2)

	// Receiving requests from Master
	go func() {
		for {
			req, err := stream.Recv()
			if err != nil {
				errChan <- err
				return
			}
			s.requestChan <- fromPbRequest(req)
		}
	}()

	// Sending responses (Chunks/Signatures) to Master
	go func() {
		for {
			select {
			case <-stream.Context().Done():
				errChan <- stream.Context().Err()
				return
			case response := <-s.responseChan:
				if err := stream.Send(toPbResponse(response)); err != nil {
					errChan <- err
					return
				}
			}
		}
	}()

	// Wait for one of them to stop (Error or stream ended)
	return <-errChan
}

func fromPbRequest(req *pb.FileDownloaderRequest) domain.FileDownloaderRequest {
	return domain.FileDownloaderRequest{
		FileID: domain.FileID(req.FileId),
		Path:   req.Path,
	}
}

func toPbResponse(resp domain.FileDownloaderResponse) *pb.FileDownloaderResponse {
	pbResp := &pb.FileDownloaderResponse{}

	if resp.FileMetadata != nil {
		pbResp.Control = &pb.FileDownloaderResponse_Metadata{
			Metadata: &pb.FileMetadata{
				MimeType: resp.FileMetadata.MimeType,
				Size:     resp.FileMetadata.Size,
			},
		}
	} else if resp.FileChunk != nil {
		pbResp.Control = &pb.FileDownloaderResponse_Chunk{
			Chunk: &pb.FileChunk{
				Data: resp.FileChunk.Chunk,
			},
		}
	} else if resp.FileSignature != nil {
		pbResp.Control = &pb.FileDownloaderResponse_Signature{
			Signature: &pb.FileSignature{
				Sha256: resp.FileSignature.Sha256,
			},
		}
	} else if resp.FileError != nil {
		pbResp.Control = &pb.FileDownloaderResponse_Error{
			Error: &pb.FileError{
				Message: resp.FileError.Message,
				Code:    pb.ErrorCode(resp.FileError.ErrorCode),
			},
		}
	}

	return pbResp
}
