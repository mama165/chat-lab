package workers

import (
	"chat-lab/domain/analyzer"
	"chat-lab/mocks"
	pb "chat-lab/proto/analyzer"
	"context"
	"fmt"
	"io"
	"log/slog"
	"testing"
	"time"

	"go.uber.org/mock/gomock"

	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func TestFileSenderWorker_Run(t *testing.T) {
	req := require.New(t)

	t.Run("Success scenario", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		defer ctrl.Finish()

		mockClient := mocks.NewMockFileAnalyzerClient(ctrl)
		mockStream := mocks.NewMockFileAnalyzerStream(ctrl)
		fileChan := make(chan *analyzer.FileAnalyzerRequest, 1)
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))

		worker := NewFileSenderWorker(mockClient, logger, fileChan, 5*time.Second)

		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()

		mockClient.EXPECT().Analyze(gomock.Any()).Return(mockStream, nil)

		mockStream.EXPECT().Send(gomock.Any()).Return(nil).Times(1)
		mockStream.EXPECT().CloseSend().Return(nil)
		mockStream.EXPECT().CloseAndRecv().Return(&pb.FileAnalyzerResponse{
			FilesReceived: 1,
			EndedAt:       timestamppb.Now(),
		}, nil)

		// English comment: Push a file to the channel and close it to trigger completion.
		go func() {
			fileChan <- &analyzer.FileAnalyzerRequest{Path: "/test/path"}
			close(fileChan)
		}()

		err := worker.Run(ctx)
		req.NoError(err)
	})

	t.Run("Server error during send", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		defer ctrl.Finish()

		mockClient := mocks.NewMockFileAnalyzerClient(ctrl)
		mockStream := mocks.NewMockFileAnalyzerStream(ctrl)
		fileChan := make(chan *analyzer.FileAnalyzerRequest, 1)
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))

		worker := NewFileSenderWorker(mockClient, logger, fileChan, 5*time.Second)

		mockClient.EXPECT().Analyze(gomock.Any()).Return(mockStream, nil)

		// English comment: Simulate a gRPC send failure.
		mockStream.EXPECT().Send(gomock.Any()).Return(fmt.Errorf("rpc error: code = Unavailable")).AnyTimes()
		mockStream.EXPECT().CloseSend().Return(nil)
		mockStream.EXPECT().CloseAndRecv().Return(nil, io.EOF)

		go func() {
			fileChan <- &analyzer.FileAnalyzerRequest{Path: "/test/fail"}
		}()

		err := worker.Run(context.Background())
		req.Error(err)
		req.Contains(err.Error(), "send error")
	})

	t.Run("Graceful shutdown on context cancellation", func(t *testing.T) {
		ctrl := gomock.NewController(t)
		defer ctrl.Finish()

		mockClient := mocks.NewMockFileAnalyzerClient(ctrl)
		mockStream := mocks.NewMockFileAnalyzerStream(ctrl)
		fileChan := make(chan *analyzer.FileAnalyzerRequest)
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))

		worker := NewFileSenderWorker(mockClient, logger, fileChan, 5*time.Second)

		ctx, cancel := context.WithCancel(context.Background())

		mockClient.EXPECT().Analyze(gomock.Any()).Return(mockStream, nil)
		mockStream.EXPECT().CloseSend().Return(nil)
		mockStream.EXPECT().CloseAndRecv().Return(nil, context.Canceled)

		// English comment: Cancel context to simulate supervisor stopping the worker.
		cancel()
		err := worker.Run(ctx)

		req.ErrorIs(err, context.Canceled)
	})
}
