package client

import (
	"chat-lab/domain/mimetypes"
	"chat-lab/domain/specialist"
	pb "chat-lab/proto/analysis"
	"context"
	"os"
	"time"

	"github.com/samber/lo"
)

type SpecialistClient struct {
	Id           specialist.Metric
	Client       pb.SpecialistServiceClient
	Process      *os.Process
	Port         int
	LastHealth   time.Time
	Capabilities []mimetypes.MIME
}

func NewSpecialistClient(id specialist.Metric,
	client pb.SpecialistServiceClient,
	process *os.Process, port int, lastHealth time.Time,
	capabilities []mimetypes.MIME,
) *SpecialistClient {
	return &SpecialistClient{
		Id: id, Client: client, Process: process, Port: port,
		LastHealth:   lastHealth,
		Capabilities: capabilities,
	}
}

// Analyze orchestrates a client-side streaming request to a specialized service.
// It first transmits document metadata, then streams the data content in 32KB chunks
// to manage memory efficiently and stay within gRPC message size limits.
// Finally, it closes the stream and returns the specialist's analysis response.
func (s *SpecialistClient) Analyze(ctx context.Context, request specialist.Request) (specialist.Response, error) {
	stream, err := s.Client.AnalyzeStream(ctx)
	if err != nil {
		return specialist.Response{}, err
	}

	if request.Metadata != nil {
		err = stream.Send(&pb.SpecialistRequest{
			Entry: &pb.SpecialistRequest_Metadata{
				Metadata: &pb.Metadata{
					MessageId: request.Metadata.MessageID,
					FileName:  request.Metadata.FileName,
					MimeType:  string(request.Metadata.MimeType),
				},
			},
		})
		if err != nil {
			return specialist.Response{}, err
		}
	}

	const chunkSize = 32 * 1024
	for i := 0; i < len(request.Chunk); i += chunkSize {
		end := i + chunkSize
		if end > len(request.Chunk) {
			end = len(request.Chunk)
		}

		err = stream.Send(&pb.SpecialistRequest{
			Entry: &pb.SpecialistRequest_Chunk{
				Chunk: request.Chunk[i:end],
			},
		})
		if err != nil {
			return specialist.Response{}, err
		}
	}

	response, err := stream.CloseAndRecv()
	if err != nil {
		return specialist.Response{}, err
	}

	return ToResponse(response), nil
}

func (s *SpecialistClient) CanHandle(mimeType mimetypes.MIME) bool {
	return lo.Contains(s.Capabilities, mimeType)
}

func ToResponse(response *pb.SpecialistResponse) specialist.Response {
	switch resp := (response.Response).(type) {
	case *pb.SpecialistResponse_DocumentData:
		return specialist.Response{
			OneOf: specialist.DocumentData{
				Title:     resp.DocumentData.Title,
				Author:    resp.DocumentData.Author,
				PageCount: resp.DocumentData.PageCount,
				Language:  resp.DocumentData.Language,
				Pages:     toPages(resp.DocumentData.Pages),
			},
		}
	case *pb.SpecialistResponse_Score:
		return specialist.Response{
			OneOf: specialist.Score{
				Score: resp.Score.Score,
				Label: resp.Score.Label,
			},
		}
	default:
		return specialist.Response{}
	}
}

func toPages(pages []*pb.Page) []specialist.Page {
	return lo.Map(pages, func(item *pb.Page, _ int) specialist.Page {
		return specialist.Page{
			Number:  item.Number,
			Content: item.Content,
		}
	})
}
