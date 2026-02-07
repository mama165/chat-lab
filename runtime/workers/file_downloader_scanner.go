package workers

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"os"
	"sync"

	"github.com/gabriel-vasile/mimetype"
	"github.com/go-playground/validator/v10"
)

type FileDownloaderScannerWorker struct {
	log           *slog.Logger
	validator     *validator.Validate
	requestChan   chan domain.FileDownloaderRequest
	responseChan  chan domain.FileDownloaderResponse
	bufferPool    *sync.Pool
	chunkSizeKb   int
	maxFileSizeMb int
}

var chunk int

func NewFileDownloaderScannerWorker(log *slog.Logger,
	requestChan chan domain.FileDownloaderRequest,
	responseChan chan domain.FileDownloaderResponse,
	chunkSizeKb, maxFileSizeMb int) *FileDownloaderScannerWorker {
	chunk = chunkSizeKb * domain.KB
	return &FileDownloaderScannerWorker{
		log:           log,
		validator:     validator.New(),
		requestChan:   requestChan,
		responseChan:  responseChan,
		chunkSizeKb:   chunkSizeKb,
		maxFileSizeMb: maxFileSizeMb,
		bufferPool: &sync.Pool{
			New: func() any {
				b := make([]byte, chunk)
				return &b
			},
		},
	}
}

// Run acts as the main orchestration loop for file download requests.
// It listens for incoming requests on the internal channel and triggers the
// processing for each. It respects the provided context for graceful shutdown.
func (s FileDownloaderScannerWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case request := <-s.requestChan:
			if err := s.Process(ctx, request); err != nil {
				s.log.Error("Failed to process file request", "error", err, "path", request.Path)
			}
		}
	}
}

// Process handles the end-to-end lifecycle of a single file download.
// It performs security checks, sniffs metadata, streams chunks, and calculates
// a SHA256 checksum on the fly to ensure data integrity.
func (s FileDownloaderScannerWorker) Process(ctx context.Context, request domain.FileDownloaderRequest) error {
	if err := s.validator.Struct(request); err != nil {
		s.sendError(ctx, "Invalid path parameters", domain.InvalidFilePath)
		return err
	}
	fileInfo, err := os.Stat(request.Path)
	if err != nil {
		s.sendError(ctx, fmt.Sprintf("file not found at: %s", request.Path), domain.NotFound)
		return err
	}

	var maxFileSize = uint64(s.maxFileSizeMb * domain.MB)
	fileSize := uint64(fileInfo.Size())

	if fileSize > maxFileSize {
		s.sendError(ctx, fmt.Sprintf("file is too large: %d bytes (limit is %d)", fileSize, maxFileSize), domain.FileTooLarge)
		return fmt.Errorf("file is too large: %d bytes (limit is %d)", fileSize, maxFileSize)
	}

	fileInfo.Mode().IsRegular()
	if fileInfo.IsDir() || !fileInfo.Mode().IsRegular() {
		s.sendError(ctx, fmt.Sprintf("entry%s is not a file", request.Path), domain.NotFile)
		return fmt.Errorf("entry%s is not a file", request.Path)
	}
	file, err := os.Open(request.Path)
	if err != nil {
		s.sendError(ctx, fmt.Sprintf("access denied to %s", request.Path), domain.AccessDenied)
		return err
	}

	defer file.Close()
	sniffBuf := make([]byte, 512)

	// Cursor is reading 512 bytes
	if _, err := file.Read(sniffBuf); err != nil {
		s.log.Error("Unable to sniff file to download", "part", len(sniffBuf))
		return err
	}

	rawMimeType := mimetype.Detect(sniffBuf).String()
	effectiveMimeType := mimetypes.ToMIME(rawMimeType)
	select {
	case <-ctx.Done():
	case s.responseChan <- domain.FileDownloaderResponse{
		FileMetadata: &domain.FileMetadata{
			RawMimeType:       rawMimeType,
			EffectiveMimeType: effectiveMimeType,
			Size:              uint64(fileInfo.Size()),
		}}:
	}

	// Cursor needs to be at the beginning for iterations by chunks
	if _, err = file.Seek(0, 0); err != nil {
		s.log.Error("Error while seeking at the beginning", "file_path", request.Path)
		return err
	}

	// Get a clean buffer
	bufPtr := s.bufferPool.Get().(*[]byte)
	mainBuf := *bufPtr
	// Ensuring it returns to the pool à the end of function
	defer s.bufferPool.Put(bufPtr)

	hash := sha256.New()

	for {
		// Read automatically move cursor
		// First iteration -> O Kb to 64 Kb
		// Second iteration -> 64 Kb to 128 Kb
		n, err := file.Read(mainBuf)
		if n > 0 {
			// Updating hash
			// Accumulating read bytes
			hash.Write(mainBuf[:n])

			// Copy buffer to prevent data corruption during buffer reuse
			// Important because reused next tour
			temp := make([]byte, n)
			copy(temp, mainBuf[:n])

			select {
			case <-ctx.Done():
				s.log.Warn("Streaming cancelled by context")
				return ctx.Err()
			case s.responseChan <- domain.FileDownloaderResponse{
				FileChunk: &domain.FileChunk{
					Chunk: temp,
				},
			}:
			}
		}

		// Handling end of file
		// Terminated properly
		// Exiting for loop
		if err != nil && err == io.EOF {
			break
		}
		if err != nil {
			s.log.Error("Error while reading file", "error", err)
			return err
		}
	}

	// Generate final hash, from everything that have been written in iteration
	signature := hash.Sum(nil)

	select {
	case <-ctx.Done():
		return ctx.Err()
	case s.responseChan <- domain.FileDownloaderResponse{
		FileSignature: &domain.FileSignature{
			Sha256: hex.EncodeToString(signature),
		},
	}:
		s.log.Info("File streaming completed successfully", "path", request.Path)
	}
	return nil
}

func (s FileDownloaderScannerWorker) sendError(ctx context.Context, msg string, code domain.ErrorCode) {
	select {
	case <-ctx.Done():
	case s.responseChan <- domain.FileDownloaderResponse{
		FileError: &domain.FileError{
			Message:   msg,
			ErrorCode: code,
		},
	}:
	}
}
