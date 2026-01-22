package event

import (
	"time"

	"github.com/google/uuid"
)

type FileAnalyse struct {
	Id         uuid.UUID
	Path       string
	DriveID    string
	Size       uint64
	Attributes uint32
	MimeType   string
	MagicBytes []byte
	ScannedAt  time.Time
	SourceType string
}

func (r FileAnalyse) Namespace() string {
	return r.DriveID
}
