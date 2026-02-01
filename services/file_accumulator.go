package services

import (
	"chat-lab/domain"
	"crypto/sha256"
	"fmt"
	"hash"
	"os"
	"sync"
)

// FileAccumulator handles concurrent reassembly of files received via gRPC streams.
// It uses mutexes to safely manage multiple file transfers simultaneously.
type FileAccumulator struct {
	tempDir string
	mu      sync.RWMutex                         // Protects access to buffers and hash maps
	buffers map[domain.FileID]*domain.FileBuffer // Map FileID -> Active file handle
	hash    map[domain.FileID]hash.Hash          // Map FileID -> Running SHA256 computation
}

// NewFileAccumulator initializes a new accumulator with the specified temporary directory.
func NewFileAccumulator(tempDir string) *FileAccumulator {
	return &FileAccumulator{
		tempDir: tempDir,
		buffers: make(map[domain.FileID]*domain.FileBuffer),
		hash:    make(map[domain.FileID]hash.Hash),
	}
}

// ProcessResponse dispatches the incoming response to the appropriate handling logic based on its content.
// It ensures thread-safe access to internal maps using both Write and Read locks.
func (a *FileAccumulator) ProcessResponse(resp domain.FileDownloaderResponse) error {
	fileID := resp.FileID

	switch {
	case resp.FileMetadata != nil:
		// Exclusive lock required to initialize new entries in the maps
		a.mu.Lock()
		defer a.mu.Unlock()

		// Create the temporary file on disk
		f, err := os.Create(fmt.Sprintf("%s/%s.tmp", a.tempDir, fileID))
		if err != nil {
			return fmt.Errorf("failed to create temp file for %s: %w", fileID, err)
		}

		a.buffers[fileID] = &domain.FileBuffer{Handle: f}
		a.hash[fileID] = sha256.New()
		return nil
	case resp.FileChunk != nil:
		// Read lock is sufficient to retrieve existing handles and hashers
		a.mu.RLock()
		buf, bufOk := a.buffers[fileID]
		h, hashOk := a.hash[fileID]
		a.mu.RUnlock()

		if !bufOk || !hashOk {
			return fmt.Errorf("no active buffer found for file %s", fileID)
		}

		// Write data to the file and update the rolling hash
		// Note: os.File.Write is thread-safe at the OS level for separate file handles
		if _, err := buf.Handle.Write(resp.FileChunk.Chunk); err != nil {
			return fmt.Errorf("failed to write chunk for %s: %w", fileID, err)
		}
		h.Write(resp.FileChunk.Chunk)

	case resp.FileSignature != nil:
		// Finalization logic handles cleanup and integrity verification
		return a.finalize(fileID, resp.FileSignature.Sha256)
	}

	return nil
}

// finalize verifies the file integrity against the expected SHA256 and cleans up resources.
func (a *FileAccumulator) finalize(fileID domain.FileID, expectedSha string) error {
	// Exclusive lock required to remove entries from the maps
	a.mu.Lock()
	buf, ok := a.buffers[fileID]
	h := a.hash[fileID]

	// Cleanup maps immediately to prevent memory leaks, even if verification fails
	delete(a.buffers, fileID)
	delete(a.hash, fileID)
	a.mu.Unlock()

	if !ok {
		return fmt.Errorf("cannot finalize: buffer not found for %s", fileID)
	}

	// Ensure the file handle is closed
	defer buf.Handle.Close()

	// Compare calculated hash with the expected signature
	actualSha := fmt.Sprintf("%x", h.Sum(nil))
	if actualSha != expectedSha {
		return fmt.Errorf("integrity check failed for %s: got %s, want %s", fileID, actualSha, expectedSha)
	}

	// Success: The file is ready to be moved to final storage or indexed in Badger
	return nil
}
