package domain

import "os"

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

// Internal representation for the accumulator
type FileBuffer struct {
	File   *os.File
	Target *FileTransfer
}
