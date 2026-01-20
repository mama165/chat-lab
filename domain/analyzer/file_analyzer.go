package analyzer

import "time"

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
type FileAnalyzerRequest struct {
	Path       string     `validate:"required,max=1024"`
	DriveID    string     `validate:"required"`
	Size       uint64     `validate:"required,gte=0"`
	Attributes uint32     `validate:"required,gte=0"`
	MimeType   string     `validate:"required,max=128"`
	MagicBytes []byte     `validate:"required,max=512"`
	ScannedAt  time.Time  `validate:"required"`
	SourceType SourceType `validate:"required,oneof=UNSPECIFIED LOCAL_FIXED REMOVABLE NETWORK"`
}

// FileAnalyzerResponse is a summary sent back by the server once the
// gRPC stream is closed by the client. It provides a synchronization
// checkpoint, confirming how many files were successfully acknowledged
// and the total volume of data processed during the session.
type FileAnalyzerResponse struct {
	FilesReceived  int64
	BytesProcessed int64
	EndedAt        time.Time
}
