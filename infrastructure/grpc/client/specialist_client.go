package client

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	pb "chat-lab/proto/specialist"
	"context"
	"os"
	"time"

	"github.com/samber/lo"
)

type SpecialistClient struct {
	Id           domain.Metric
	Client       pb.SpecialistServiceClient
	Process      *os.Process
	Port         int
	LastHealth   time.Time
	Capabilities []mimetypes.MIME
}

func NewSpecialistClient(id domain.Metric,
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
func (s *SpecialistClient) Analyze(ctx context.Context, request domain.Request) (domain.Response, error) {
	stream, err := s.Client.AnalyzeStream(ctx)
	if err != nil {
		return domain.Response{}, err
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
			return domain.Response{}, err
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
			return domain.Response{}, err
		}
	}

	response, err := stream.CloseAndRecv()
	if err != nil {
		return domain.Response{}, err
	}

	return ToResponse(response), nil
}

func (s *SpecialistClient) CanHandle(mimeType mimetypes.MIME) bool {
	return lo.Contains(s.Capabilities, mimeType)
}

func ToResponse(response *pb.SpecialistResponse) domain.Response {
	switch resp := (response.Response).(type) {
	case *pb.SpecialistResponse_DocumentData:
		return domain.Response{
			OneOf: domain.DocumentData{
				Title:     resp.DocumentData.Title,
				Author:    resp.DocumentData.Author,
				PageCount: resp.DocumentData.PageCount,
				Language:  resp.DocumentData.Language,
				Pages:     toPages(resp.DocumentData.Pages),
			},
		}
	case *pb.SpecialistResponse_Score:
		return domain.Response{
			OneOf: domain.Score{
				Score: resp.Score.Score,
				Label: resp.Score.Label,
			},
		}
	case *pb.SpecialistResponse_Audio:
		return domain.Response{
			OneOf: domain.AudioData{
				Transcription: resp.Audio.Transcription,
				Duration:      resp.Audio.DurationSec,
				Language:      resp.Audio.Language,
			},
		}
	default:
		return domain.Response{}
	}
}

func toPages(pages []*pb.Page) []domain.Page {
	return lo.Map(pages, func(item *pb.Page, _ int) domain.Page {
		return domain.Page{
			Number:  item.Number,
			Content: item.Content,
		}
	})
}
