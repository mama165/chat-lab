package analyzer

import "time"

type SourceType int

const (
	SourceTypeUnspecified SourceType = iota
	SourceTypeLocalFixed  SourceType = 1
	SourceTypeRemovable   SourceType = 2
	SourceTypeNetwork     SourceType = 3
)

type FileAnalyzerRequest struct {
	Path       string `validate:"required"`
	DriveID    string `validate:"required"`
	Size       uint64 `validate:"required,gte=0"`
	Attributes uint32 `validate:"required,gte=0"`
	MimeType   string `validate:"required"`
	MagicBytes []byte
	ScannedAt  time.Time  `validate:"required"`
	SourceType SourceType `validate:"required"`
}

type FileAnalyzerResponse struct {
	FilesReceived  int64
	BytesProcessed int64
	EndedAt        time.Time
}
