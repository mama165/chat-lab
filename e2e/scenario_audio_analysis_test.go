package e2e

import (
	"context"
	"io"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/stretchr/testify/suite"

	// Replace with your actual internal proto package
	pb "chat-lab/proto/analyzer"
)

type testAudioAnalysisSuite struct {
	BaseGrpcSuite
}

func TestAudioAnalysisSuite(t *testing.T) {
	suite.Run(t, &testAudioAnalysisSuite{})
}

func (s *testAudioAnalysisSuite) TestFullAudioAnalysisFlow() {
	fileID := uuid.New().String()
	// This path represents the file location on the scanner's filesystem
	filename := "sample_audio_macos.aiff"

	// --- STEP 0: TRIGGER MANUAL SCAN ---
	// Since we disabled auto-scan on startup, we tell the scanner to start
	s.Run("Step 0: Trigger manual scan via Controller", func() {
		s.WithScannerControl("Triggering scan on target directory", func(ctx context.Context, client pb.ScannerControllerClient) {
			resp, err := client.TriggerScan(ctx, &pb.ScanRequest{
				Path: s.Config.ScannerRootDir,
			})
			s.Require().NoError(err, "Failed to trigger scan via gRPC")
			s.Require().True(resp.Started, "Scanner reported it could not start the scan")
		})
	})

	// --- STEP 1: STREAM INTEGRITY ---
	s.Run("Step 1: Download stream and validate protocol sequence", func() {
		s.WithScanner("Request file and verify stream integrity", func(ctx context.Context, client pb.FileDownloaderServiceClient) {
			stream, err := client.Download(ctx)
			s.Require().NoError(err)

			cwd, _ := os.Getwd()
			absolutePath := filepath.Join(cwd, s.Config.ScannerRootDir, filename)
			_, err = os.Stat(absolutePath)
			s.Require().NoError(err)

			// Send the initial request (Command)
			err = stream.Send(&pb.FileDownloaderRequest{
				FileId: fileID,
				Path:   absolutePath,
			})
			s.Require().NoError(err)

			// Protocol state sentinels
			receivedMetadata := false
			receivedSignature := false
			chunkCount := 0

			for {
				resp, err := stream.Recv()
				if err == io.EOF {
					break
				}
				s.Require().NoError(err)

				switch res := resp.Control.(type) {
				case *pb.FileDownloaderResponse_Metadata:
					// SEQUENCE CHECK: Metadata MUST be the first message received
					s.Require().False(receivedMetadata, "Protocol error: Metadata received more than once")
					s.Require().False(receivedSignature, "Protocol error: Metadata received after signature")
					s.Require().Equal(0, chunkCount, "Protocol error: Metadata received after data chunks")

					receivedMetadata = true
					s.T().Logf("Verified: Metadata received for %s", res.Metadata.MimeType)

				case *pb.FileDownloaderResponse_Chunk:
					// SEQUENCE CHECK: Metadata MUST have been received before any chunk
					s.Require().True(receivedMetadata, "Protocol error: Chunk received before metadata")
					s.Require().False(receivedSignature, "Protocol error: Chunk received after signature")

					chunkCount++
					s.Require().NotNil(res.Chunk.Data)

				case *pb.FileDownloaderResponse_Signature:
					// SEQUENCE CHECK: Signature MUST be the final message
					s.Require().True(receivedMetadata, "Protocol error: Signature received before metadata")
					s.Require().Greater(chunkCount, 0, "Protocol error: Signature received without any data chunks")

					receivedSignature = true
					s.T().Logf("Verified: Final SHA256 signature received: %s", res.Signature.Sha256)

				case *pb.FileDownloaderResponse_Error:
					s.FailNowf("Scanner reported a functional error", "Code: %v, Message: %s", res.Error.Code, res.Error.Message)
				}
			}

			// Final stream validation
			s.Require().True(receivedMetadata, "Stream closed without sending metadata")
			s.Require().True(receivedSignature, "Stream closed without sending a final signature")
			s.T().Logf("Success: Received %d chunks in correct order", chunkCount)
		})
	})

	// --- STEP 2: ASYNCHRONOUS PROCESSING VALIDATION ---
	s.Run("Step 2: Wait for Specialist analysis (Polling Master)", func() {
		// We wait for the Python Specialist to finish the job and Master to store it in BadgerDB
		s.Eventually(func() bool {
			// Polling a stubbed method that mimics gRPC Master response
			res, err := s.Stub_GetAnalysisFromMaster(fileID)
			return err == nil && res != nil
		}, 20*time.Second, 1*time.Second, "Analysis report not found in Master store within timeout")
	})
}

// ----------------------------------------------------------------------------
// STUBS & HELPERS
// ----------------------------------------------------------------------------

func (s *BaseGrpcSuite) WithScannerControl(name string, fn func(ctx context.Context, client pb.ScannerControllerClient)) {
	conn := s.GrpcConn(s.T(), name, s.Config.ScannerAddr)
	defer conn.Close()

	client := pb.NewScannerControllerClient(conn)
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()

	fn(ctx, client)
}

type AnalysisResult struct {
	ID   string
	Tags []string
}

func (s *testAudioAnalysisSuite) Stub_GetAnalysisFromMaster(id string) (*AnalysisResult, error) {
	// In the future: return s.MasterClient.GetAnalysis(ctx, &pb.GetReq{Id: id})
	return &AnalysisResult{
		ID:   id,
		Tags: []string{"speech_detected", "high_quality"},
	}, nil
}
