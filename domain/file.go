package domain

import "chat-lab/domain/mimetypes"

// FileID identifies a scanned file across the pipeline (scan, storage, analysis).
type FileID string

const KB = 1024
const MB = KB * KB

// AnalysisJob is a scanned file waiting to be analyzed by the specialists.
// It is created once the file metadata has been stored, so that the specialist
// results can be merged into an existing record.
type AnalysisJob struct {
	DriveID           string
	FileID            FileID
	Path              string
	Size              uint64
	EffectiveMimeType mimetypes.MIME
}
