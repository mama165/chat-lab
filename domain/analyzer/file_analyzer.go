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
	Path       string
	DriveID    string
	Size       uint64
	Attributes uint32
	MimeType   string
	MagicBytes []byte
	ScannedAt  time.Time
	SourceType SourceType
}

type FileAnalyzerResponse struct {
	FilesReceived  int64
	BytesProcessed int64
	EndedAt        time.Time
}
