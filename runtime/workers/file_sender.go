package workers

import (
	"chat-lab/domain/analyzer"
	pb "chat-lab/proto/analyzer"
	"context"
	"fmt"
	"io"
	"log/slog"
	"time"

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
	// Créer un contexte avec timeout pour éviter les blocages infinis
	streamCtx, cancel := context.WithTimeout(ctx, 30*time.Minute)
	defer cancel()

	stream, err := w.client.Analyze(streamCtx)
	if err != nil {
		return fmt.Errorf("unable to open the stream to analyze files: %w", err)
	}

	// Canal pour signaler quand on a fini d'envoyer
	sendDone := make(chan error, 1)

	// Goroutine pour envoyer les fichiers
	go func() {
		defer func() {
			// Toujours fermer l'envoi quand on a fini
			if err := stream.CloseSend(); err != nil && err != io.EOF {
				w.log.Debug("Error closing send", "error", err)
			}
		}()

		for {
			select {
			case <-ctx.Done():
				sendDone <- ctx.Err()
				return

			case req, ok := <-w.fileChan:
				if !ok {
					// Canal fermé, on a fini d'envoyer
					w.log.Info("file scanner channel closed, finishing send")
					sendDone <- nil
					return
				}

				w.log.Debug("Sending file scan to analyzer", "event", req.Path)
				if err := stream.Send(toPbFileAnalyzerRequest(req)); err != nil {
					w.log.Warn("Failed to send file to analyzer", "path", req.Path, "error", err)
					sendDone <- fmt.Errorf("send error: %w", err)
					return
				}
			}
		}
	}()

	// Attendre que l'envoi soit terminé
	sendErr := <-sendDone
	if sendErr != nil && sendErr != context.Canceled {
		w.log.Warn("Send goroutine finished with error", "error", sendErr)
	}

	// Maintenant qu'on a fermé l'envoi, on peut recevoir la réponse finale
	response, err := stream.CloseAndRecv()
	if err != nil {
		if err == io.EOF || sendErr == context.Canceled {
			w.log.Info("Stream closed normally")
			return sendErr
		}
		return fmt.Errorf("error receiving final response: %w", err)
	}

	w.log.Info("File transfer complete",
		"files_received", response.FilesReceived,
		"bytes_processed", response.BytesProcessed,
		"ended_at", response.EndedAt.AsTime().Format(time.RFC3339),
	)

	return sendErr
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
