package domain

import "chat-lab/domain/mimetypes"

type FileID string

type FileDownloaderRequest struct {
	FileID FileID `validate:"required,max=1024"`
	Path   string `validate:"required,max=1024"`
}

type FileDownloaderResponse struct {
	FileID        FileID
	FileMetadata  *FileMetadata
	FileChunk     *FileChunk
	FileSignature *FileSignature
	FileError     *FileError
}

type FileMetadata struct {
	RawMimeType       string
	EffectiveMimeType mimetypes.MIME
	Size              uint64
}

type FileChunk struct {
	Chunk []byte
}

type FileSignature struct {
	Sha256 string
}

type FileError struct {
	Message   string
	ErrorCode ErrorCode
}

type ErrorCode int

type TmpFileLocation struct {
	FileID            FileID
	TmpFilePath       string
	EffectiveMimeType mimetypes.MIME
}

const (
	InvalidFilePath = iota
	NotFound
	AccessDenied
	NotFile
	FileTooLarge
	ChecksumMismatch
)

const KB = 1024
const MB = KB * KB
