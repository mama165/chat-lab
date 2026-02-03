package workers

import (
	"chat-lab/contract"
	"chat-lab/domain"
	pb "chat-lab/proto/analyzer"
	"context"
	"io"
	"log/slog"
)

type FileDownloaderMasterWorker struct {
	log         *slog.Logger
	requestChan <-chan domain.FileDownloaderRequest
	grpcClient  pb.FileDownloaderServiceClient
	accumulator contract.IFileAccumulator
}

func NewFileDownloaderMasterWorker(
	log *slog.Logger,
	requestChan <-chan domain.FileDownloaderRequest,
	grpcClient pb.FileDownloaderServiceClient,
	accumulator contract.IFileAccumulator,
) *FileDownloaderMasterWorker {
	return &FileDownloaderMasterWorker{
		log:         log,
		requestChan: requestChan,
		grpcClient:  grpcClient,
		accumulator: accumulator,
	}
}

func (w *FileDownloaderMasterWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case req, ok := <-w.requestChan:
			if !ok {
				w.log.Debug("Channel is closed")
				return nil
			}
			w.handleDownload(ctx, req)
		}
	}
}

func (w *FileDownloaderMasterWorker) handleDownload(ctx context.Context, req domain.FileDownloaderRequest) {
	stream, err := w.grpcClient.Download(ctx)
	if err != nil {
		w.log.Error("Failed to open gRPC stream", "file_id", req.FileID, "error", err)
		return
	}

	if err = stream.Send(&pb.FileDownloaderRequest{FileId: string(req.FileID), Path: req.Path}); err != nil {
		w.log.Error("Failed to send request via stream", "error", err)
		return
	}

	for {
		resp, err := stream.Recv()
		if err == io.EOF {
			w.log.Info("Transfer finished", "file_id", req.FileID)
			break
		}
		if err != nil {
			w.log.Error("Stream recv error", "file_id", req.FileID, "error", err)
			break
		}

		// Accumulator physically write in master disk
		if err := w.accumulator.ProcessResponse(fromPbResponse(resp, req.FileID)); err != nil {
			w.log.Error("Accumulator failed to process chunk", "error", err)
			break
		}
	}
}

func fromPbResponse(pbResp *pb.FileDownloaderResponse, id domain.FileID) domain.FileDownloaderResponse {
	res := domain.FileDownloaderResponse{
		FileID: id,
	}

	switch c := pbResp.Control.(type) {
	case *pb.FileDownloaderResponse_Metadata:
		res.FileMetadata = &domain.FileMetadata{
			MimeType: c.Metadata.MimeType,
			Size:     c.Metadata.Size,
		}
	case *pb.FileDownloaderResponse_Chunk:
		res.FileChunk = &domain.FileChunk{
			Chunk: c.Chunk.Data,
		}
	case *pb.FileDownloaderResponse_Signature:
		res.FileSignature = &domain.FileSignature{
			Sha256: c.Signature.Sha256,
		}
	case *pb.FileDownloaderResponse_Error:
		res.FileError = &domain.FileError{
			Message:   c.Error.Message,
			ErrorCode: domain.ErrorCode(c.Error.Code),
		}
	}

	return res
}
