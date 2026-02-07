package domain

import (
	"chat-lab/domain/mimetypes"
	"os"
)

// FileTransfer state to track progress in Badger
type FileTransfer struct {
	FileID   FileID
	Path     string
	Size     int64
	Received int64
	Sha256   string
	Status   TransferStatus
}

type TransferStatus int

const (
	StatusPending TransferStatus = iota
	StatusInProgress
	StatusCompleted
	StatusFailed
)

// FileBuffer Internal representation for the accumulator
type FileBuffer struct {
	File              *os.File
	Path              string
	EffectiveMimeType mimetypes.MIME
	Target            *FileTransfer
}
