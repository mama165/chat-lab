package event

import (
	"chat-lab/domain/mimetypes"
	"time"

	"github.com/google/uuid"
)

const (
	FileAnalyzeType Type = "FILE_ANALYZE"
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
