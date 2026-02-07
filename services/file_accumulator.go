package services

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"context"
	"crypto/sha256"
	"fmt"
	"hash"
	"os"
	"path/filepath"
	"sync"
)

// FileAccumulator handles concurrent reassembly of files received via gRPC streams.
// It uses mutexes to safely manage multiple file transfers simultaneously.
type FileAccumulator struct {
	tempDir             string
	mu                  sync.RWMutex                         // Protects access to buffers and hash maps
	buffers             map[domain.FileID]*domain.FileBuffer // Map FileID -> Active file handle
	hash                map[domain.FileID]hash.Hash          // Map FileID -> Running SHA256 computation
	tmpFileLocationChan chan domain.TmpFileLocation
}

func NewFileAccumulator(
	tempDir string,
	tmpFileLocationChan chan domain.TmpFileLocation) *FileAccumulator {
	return &FileAccumulator{
		tempDir:             tempDir,
		buffers:             make(map[domain.FileID]*domain.FileBuffer),
		hash:                make(map[domain.FileID]hash.Hash),
		tmpFileLocationChan: tmpFileLocationChan,
	}
}

// ProcessResponse dispatches the incoming response to the appropriate handling logic based on its content.
// It ensures thread-safe access to internal maps using both Write and Read locks.
func (a *FileAccumulator) ProcessResponse(ctx context.Context, resp domain.FileDownloaderResponse) error {
	fileID := resp.FileID

	switch {
	case resp.FileMetadata != nil:
		// Exclusive lock required to initialize new entries in the maps
		a.mu.Lock()
		defer a.mu.Unlock()

		// Create the temporary file on disk
		// /tmp/{directory]/8c9bf0f9-7f1f-4f39-8caf-6cfb5eb29665.tmp
		filename := fmt.Sprintf("%s.tmp", fileID)
		path := filepath.Join(a.tempDir, filename)
		f, err := os.Create(path)
		if err != nil {
			return fmt.Errorf("failed to create temp file for %s: %w", fileID, err)
		}

		a.buffers[fileID] = &domain.FileBuffer{File: f, Path: path, EffectiveMimeType: resp.FileMetadata.EffectiveMimeType}
		a.hash[fileID] = sha256.New()
		return nil
	case resp.FileChunk != nil:
		// Read lock is sufficient to retrieve existing handles and hashes
		a.mu.RLock()
		fileBuffer, bufOk := a.buffers[fileID]
		h, hashOk := a.hash[fileID]
		a.mu.RUnlock()

		if !bufOk || !hashOk {
			return fmt.Errorf("no active buffer found for file %s", fileID)
		}

		// Write data to the file and update the rolling hash
		// Note: os.File.Write is thread-safe at the OS level for separate file handles
		if _, err := fileBuffer.File.Write(resp.FileChunk.Chunk); err != nil {
			return fmt.Errorf("failed to write chunk for %s: %w", fileID, err)
		}
		h.Write(resp.FileChunk.Chunk)

	case resp.FileSignature != nil:
		// Finalization logic handles cleanup and integrity verification
		tmpPath, mimeType, err := a.finalize(fileID, resp.FileSignature.Sha256)
		if err != nil {
			return err
		}
		select {
		case <-ctx.Done():
			return ctx.Err()
		case a.tmpFileLocationChan <- domain.TmpFileLocation{
			FileID:            fileID,
			TmpFilePath:       tmpPath,
			EffectiveMimeType: mimeType,
		}:
		}
	}
	return nil
}

// finalize verifies the file integrity against the expected SHA256 and cleans up resources.
func (a *FileAccumulator) finalize(fileID domain.FileID, expectedSha string) (string, mimetypes.MIME, error) {
	// Exclusive lock required to remove entries from the maps
	a.mu.Lock()
	fileBuffer, ok := a.buffers[fileID]

	// Get accumulated hash (sha256)
	h := a.hash[fileID]

	// Cleanup maps immediately to prevent memory leaks, even if verification fails
	delete(a.buffers, fileID)
	delete(a.hash, fileID)
	a.mu.Unlock()

	if !ok {
		return "", "", fmt.Errorf("cannot finalize: buffer not found for %s", fileID)
	}

	// Ensure the file handle is closed
	defer fileBuffer.File.Close()

	signature := h.Sum(nil)
	// Compare calculated hash with the expected signature
	actualSha := fmt.Sprintf("%x", signature)
	if actualSha != expectedSha {
		return "", "", fmt.Errorf("integrity check failed for %s: got %s, want %s", fileID, actualSha, expectedSha)
	}

	// Success: The file is ready to be moved to final storage or indexed in Badger
	return fileBuffer.Path, fileBuffer.EffectiveMimeType, nil
}
