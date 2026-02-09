package event

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"time"

	"github.com/google/uuid"
)

const (
	FileAnalyzeType     Type = "FILE_ANALYZE"
	ProcessedResultType      = "PROCESSED_RESULT"
)

type FileAnalyse struct {
	Id                uuid.UUID
	Path              string
	DriveID           string
	Size              uint64
	Attributes        uint32
	RawMimeType       string
	EffectiveMimeType mimetypes.MIME
	MagicBytes        []byte
	ScannedAt         time.Time
	SourceType        string
}

func (r FileAnalyse) Namespace() string {
	return r.DriveID
}

type AnalysisSegment struct {
	DriveID string
	FileID  domain.FileID
	Metrics []AnalysisMetric
}

type AnalysisMetric struct {
	ID            *domain.Metric
	Resp          *domain.Response
	SpecialistErr error
}

func (r AnalysisSegment) Namespace() string {
	return r.DriveID
}
