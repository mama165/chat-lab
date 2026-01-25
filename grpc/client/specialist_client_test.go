package client

import (
	"chat-lab/domain/specialist"
	pb "chat-lab/proto/analysis"
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/assert"
	"google.golang.org/grpc"
)

// mockSpecialistStream simulates the gRPC client-side stream.
// It embeds the interface to avoid implementing all gRPC internal methods.
type mockSpecialistStream struct {
	pb.SpecialistService_AnalyzeStreamClient
	sentRequests []*pb.SpecialistRequest
	sendFunc     func(*pb.SpecialistRequest) error
	response     *pb.SpecialistResponse
	err          error
}

func newMockSpecialistStream() *mockSpecialistStream {
	return &mockSpecialistStream{
		response: &pb.SpecialistResponse{
			Response: &pb.SpecialistResponse_Score{
				Score: &pb.Score{Score: 0.99, Label: "OK"},
			},
		},
	}
}

// Send intercepts gRPC requests and allows custom error injection via sendFunc.
func (m *mockSpecialistStream) Send(req *pb.SpecialistRequest) error {
	m.sentRequests = append(m.sentRequests, req)
	if m.sendFunc != nil {
		return m.sendFunc(req)
	}
	return m.err
}

func (m *mockSpecialistStream) CloseAndRecv() (*pb.SpecialistResponse, error) {
	return m.response, m.err
}

// mockServiceClient simulates the generated gRPC service client.
type mockServiceClient struct {
	pb.SpecialistServiceClient
	stream *mockSpecialistStream
	err    error
}

func (m *mockServiceClient) AnalyzeStream(_ context.Context, _ ...grpc.CallOption) (pb.SpecialistService_AnalyzeStreamClient, error) {
	return m.stream, m.err
}

func TestSpecialistClient_Analyze_Chunking(t *testing.T) {
	ass := assert.New(t)
	mockStream := newMockSpecialistStream()
	mockClient := &mockServiceClient{stream: mockStream}
	sClient := NewSpecialistClient("test-id", mockClient, nil, 50051, time.Now())

	// Given a large chunk (100 KB) to trigger chunking logic (32KB * 3 + leftover)
	part := 100
	largeData := make([]byte, part*1024)
	for i := range largeData {
		largeData[i] = byte(i % 256)
	}

	req := specialist.Request{
		Metadata: &specialist.Metadata{
			MessageID: "msg-123",
			FileName:  "test.pdf",
		},
		Chunk: largeData,
	}

	// When analyzing file bytes
	resp, err := sClient.Analyze(context.Background(), req)

	// We expect: 1 message for Metadata + 4 messages for 100KB (32+32+32+4) = 5 messages total
	// Then 1 metadata and 4 messages are sent
	ass.NoError(err)
	ass.NotNil(resp)
	ass.Equal(5, len(mockStream.sentRequests))

	// And first message is Metadata
	firstMsg := mockStream.sentRequests[0].GetMetadata()
	ass.NotNil(firstMsg)
	ass.Equal("msg-123", firstMsg.MessageId)
	ass.Equal("test.pdf", firstMsg.FileName)

	var totalReceived int
	for _, msg := range mockStream.sentRequests[1:] {
		chunk := msg.GetChunk()
		// And chunk does nots exceed 32KB
		ass.True(len(chunk) <= 32*1024)
		totalReceived += len(chunk)
	}
	// And total received data matches the original size
	ass.Equal(part*1024, totalReceived)
}

func TestSpecialistClient_Analyze_NetworkErrorDuringChunks(t *testing.T) {
	ass := assert.New(t)
	mockStream := newMockSpecialistStream()
	mockClient := &mockServiceClient{stream: mockStream}
	sClient := NewSpecialistClient("test-id", mockClient, nil, 50051, time.Now())

	// Given two chunks of 64KB
	data := make([]byte, 64*1024)

	// Given a network failure after the first successful send (Metadata)
	sendCount := 0
	mockStream.sendFunc = func(req *pb.SpecialistRequest) error {
		sendCount++
		if sendCount > 1 {
			return errors.New("connection reset by peer")
		}
		return nil
	}

	req := specialist.Request{
		Metadata: &specialist.Metadata{MessageID: "err-456"},
		Chunk:    data,
	}

	// When analyzing file bytes
	_, err := sClient.Analyze(context.Background(), req)

	// Then the error is as expected
	ass.Error(err)
	ass.Contains(err.Error(), "connection reset by peer")

	// And only two calls have been done  => 1 metadata (success) + 1 chunk (failure)
	ass.Equal(2, sendCount)
}
