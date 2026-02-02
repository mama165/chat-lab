package workers

import (
	"chat-lab/domain/analyzer"
	"chat-lab/infrastructure/grpc/client"
	pb "chat-lab/proto/analyzer"
	"context"
	"fmt"
	"io"
	"log/slog"
	"time"

	"google.golang.org/protobuf/types/known/timestamppb"
)

type FileSenderWorker struct {
	client   client.FileAnalyzerClient
	log      *slog.Logger
	fileChan chan *analyzer.FileAnalyzerRequest
}

func NewFileSenderWorker(
	client client.FileAnalyzerClient,
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
// It implements backpressure via a semaphore and ensures a graceful shutdown by handling
// context cancellation and proper gRPC stream closure.
func (w FileSenderWorker) Run(ctx context.Context) error {
	w.log.Info("🚀 FileSenderWorker starting")

	// Open the gRPC stream using the supervisor's context.
	stream, err := w.client.Analyze(ctx)
	if err != nil {
		return fmt.Errorf("unable to open the stream to analyze files: %w", err)
	}
	w.log.Info("📡 gRPC stream opened successfully")

	// Backpressure: Limit the number of concurrent messages in flight to 100.
	const maxInFlight = 100
	inFlightSemaphore := make(chan struct{}, maxInFlight)

	sendErrChan := make(chan error, 1)
	fileCount := 0

	// Launch the sending goroutine.
	go func() {
		defer func() {
			w.log.Info("🔒 Closing send stream", "total_files_sent", fileCount)
			// Signal the server that the client has finished sending data.
			if err := stream.CloseSend(); err != nil && err != io.EOF {
				w.log.Debug("Error closing send", "error", err)
			}
		}()

		// Progress monitoring: Log transfer status every 10 seconds.
		ticker := time.NewTicker(10 * time.Second)
		defer ticker.Stop()

		for {
			select {
			case <-ticker.C:
				w.log.Info("📊 Progress",
					"files_sent", fileCount,
					"in_flight", len(inFlightSemaphore),
				)

			case <-ctx.Done():
				// Stop requested by the supervisor or user interruption.
				sendErrChan <- ctx.Err()
				return

			case req, ok := <-w.fileChan:
				if !ok {
					// Input channel closed: normal end of scanning process.
					w.log.Info("📪 fileChan closed, finishing transfer")
					sendErrChan <- nil
					return
				}

				// Backpressure: Wait for an available slot before sending.
				select {
				case inFlightSemaphore <- struct{}{}:
					// Slot acquired.
				case <-ctx.Done():
					sendErrChan <- ctx.Err()
					return
				}

				// Forward the metadata to the Master server.
				if err := stream.Send(toPbFileAnalyzerRequest(req)); err != nil {
					<-inFlightSemaphore // Release slot on failure.
					w.log.Warn("❌ Failed to send file", "path", req.Path, "error", err)
					sendErrChan <- fmt.Errorf("send error: %w", err)
					return
				}

				fileCount++

				// Release slot: stream.Send blocks if gRPC internal buffers are full.
				<-inFlightSemaphore
			}
		}
	}()

	// Wait for the sending goroutine to finish or encounter an error.
	sendErr := <-sendErrChan

	// Log technical errors, but ignore simple context cancellations for cleaner logs.
	if sendErr != nil && sendErr != context.Canceled {
		w.log.Warn("⚠️ Send goroutine finished with error", "error", sendErr)
	}

	// Finalization: Wait for the server to process all data and send the summary.
	w.log.Info("📥 Waiting for server final response...")
	response, err := stream.CloseAndRecv()
	if err != nil {
		// Prioritize returning the send error if it exists.
		if sendErr != nil && sendErr != context.Canceled {
			return sendErr
		}
		// File normal closure scenarios.
		if err == io.EOF || sendErr == context.Canceled {
			return sendErr
		}
		return fmt.Errorf("error receiving final response: %w", err)
	}

	// Final success log with server-side statistics.
	w.log.Info("🎉 Transfer complete",
		"files_received", response.FilesReceived,
		"bytes_processed", response.BytesProcessed,
		"server_ended_at", response.EndedAt.AsTime().Format(time.RFC3339),
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
