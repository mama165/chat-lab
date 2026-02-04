package workers

import (
	"chat-lab/domain/analyzer"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestFileScannerWorker_Run(t *testing.T) {
	req := require.New(t)

	// t.TempDir automatically cleans up after the test.
	tempDir := t.TempDir()

	// Create a dummy file structure for testing.
	file1 := filepath.Join(tempDir, "file1.txt")
	req.NoError(os.WriteFile(file1, []byte("test content"), 0644))

	subDir := filepath.Join(tempDir, "subdir")
	req.NoError(os.Mkdir(subDir, 0755))

	file2 := filepath.Join(subDir, "file2.log")
	req.NoError(os.WriteFile(file2, []byte("more content"), 0644))

	t.Run("Full scan execution", func(t *testing.T) {
		// Initialize all dependencies for the concurrent worker.
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))
		counter := analyzer.NewCounterFileScanner()

		// Use buffered channels to avoid deadlocks during the test
		dirChan := make(chan string, 10)
		fileChan := make(chan *analyzer.FileAnalyzerRequest, 10)

		var scanWG sync.WaitGroup
		var workersWG sync.WaitGroup

		worker := NewFileScannerWorker(
			logger,
			"drive-123",
			counter,
			dirChan,
			fileChan,
			&scanWG,
			&workersWG,
			70,
			90,
		)

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()

		// Seed the scan with the root directory.
		scanWG.Add(1)
		dirChan <- tempDir

		// Start the worker in a separate goroutine.
		workersWG.Add(1)
		go func() {
			_ = worker.Run(ctx)
		}()

		// Wait for the scan to finish (all directories explored).
		// We use a goroutine to wait so we don't block the main test thread.
		done := make(chan struct{})
		go func() {
			scanWG.Wait()
			close(done)
		}()

		select {
		case <-done:
			// Scan finished normally
		case <-ctx.Done():
			t.Fatal("Test timed out before scan completed")
		}

		// Shutdown the worker by closing the work channel.
		close(dirChan)
		workersWG.Wait()

		// Assertions
		req.Equal(uint64(2), counter.FilesScanned, "Should have found 2 files")
		req.Equal(uint64(2), counter.DirsScanned, "Should have scanned root + subdir")
		req.Equal(uint64(24), counter.BytesProcessed, "Total size should match content length")

		// Verify fileChan content
		req.Equal(2, len(fileChan))
		firstFile := <-fileChan
		req.NotEmpty(firstFile.MimeType)
		req.NotEmpty(firstFile.MagicBytes)
	})

	t.Run("Throttle mechanism check", func(t *testing.T) {
		// Verify that throttle doesn't crash when channel is empty.
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))
		fileChan := make(chan *analyzer.FileAnalyzerRequest, 10) // capacity 10

		worker := NewFileScannerWorker(
			logger,
			"id",
			analyzer.NewCounterFileScanner(),
			nil, fileChan, nil, nil,
			70,
			90)

		// 0% usage, should not pause
		start := time.Now()
		err := worker.throttle(context.Background())
		req.NoError(err)
		req.Less(time.Since(start), 10*time.Millisecond, "Should not throttle at 0% usage")

		// Fill the channel above hard limit (90% of 10 = 9 messages)
		for i := 0; i < 10; i++ {
			fileChan <- &analyzer.FileAnalyzerRequest{}
		}

		start = time.Now()
		// Start a context that will cancel if the throttle hangs too long
		ctx, cancel := context.WithTimeout(context.Background(), 500*time.Millisecond)
		defer cancel()
		err = worker.throttle(ctx)

		req.NoError(err)
		req.GreaterOrEqual(time.Since(start), 200*time.Millisecond, "Should have throttled for at least 200ms")
	})
}
