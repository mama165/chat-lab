package analyzer

import (
	"time"
)

type SourceType string

const (
	UNSPECIFIED SourceType = "UNSPECIFIED"
	LOCALFIXED  SourceType = "LOCAL_FIXED"
	REMOVABLE   SourceType = "REMOVABLE"
	NETWORK     SourceType = "NETWORK"
)

// FileAnalyzerRequest represents a single data point captured by the scanner.
// It carries the minimal set of metadata and raw bytes (sniffing) required
// for the central orchestrator to identify, filter, and decide the indexing
// priority of a file without transferring its entire content.
// MagicBytes 64 bytes is enough
type FileAnalyzerRequest struct {
	Path       string     `validate:"required,max=1024"`
	DriveID    string     `validate:"required"`
	Size       uint64     `validate:"gte=0"`
	Attributes uint32     `validate:"gte=0"`
	MimeType   string     `validate:"required,max=128"`
	MagicBytes []byte     `validate:"required,max=64"`
	ScannedAt  time.Time  `validate:"required"`
	SourceType SourceType `validate:"required,oneof=UNSPECIFIED LOCAL_FIXED REMOVABLE NETWORK"`
}

// CountAnalyzedFiles is a summary sent back by the server once the
// gRPC stream is closed by the client. It provides a synchronization
// checkpoint, confirming how many files were successfully acknowledged
// and the total volume of data processed during the session.
type CountAnalyzedFiles struct {
	FilesReceived  uint64
	BytesProcessed uint64
}

func NewCountAnalyzedFiles() CountAnalyzedFiles {
	return CountAnalyzedFiles{
		FilesReceived:  0,
		BytesProcessed: 0,
	}
}
