package workers

import (
	"chat-lab/domain/analyzer"
	"chat-lab/observability"
	"context"
	"fmt"
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

	// Create a dummy file structure for testing. Only supported types (detected from
	// the first bytes, not from the extension) are reported by the scanner.
	pdfContent := []byte("%PDF-1.4 test")
	pngContent := []byte("\x89PNG\r\n\x1a\n test")

	file1 := filepath.Join(tempDir, "file1.pdf")
	req.NoError(os.WriteFile(file1, pdfContent, 0644))

	subDir := filepath.Join(tempDir, "subdir")
	req.NoError(os.Mkdir(subDir, 0755))

	file2 := filepath.Join(subDir, "file2.png")
	req.NoError(os.WriteFile(file2, pngContent, 0644))

	// A plain text file is not a supported type and must be ignored.
	req.NoError(os.WriteFile(filepath.Join(subDir, "notes.txt"), []byte("just text"), 0644))

	t.Run("Full scan execution", func(t *testing.T) {
		// Initialize all dependencies for the concurrent worker.
		logger := slog.New(slog.NewTextHandler(io.Discard, nil))
		monitoring := observability.NewMonitoringManager(logger)

		// Use buffered channels to avoid deadlocks during the test
		dirChan := make(chan string, 10)
		fileChan := make(chan *analyzer.FileAnalyzerRequest, 10)

		var scanWG sync.WaitGroup

		worker := NewFileScannerWorker(
			logger,
			"drive-123",
			monitoring,
			dirChan,
			fileChan,
			&scanWG,
			70,
			90,
		)

		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()

		// Seed the scan with the root directory.
		scanWG.Add(1)
		dirChan <- tempDir

		// Start the worker in a separate goroutine.
		workerDone := make(chan struct{})
		go func() {
			defer close(workerDone)
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
		<-workerDone

		// Assertions
		req.Equal(uint64(2), monitoring.FilesFound, "Should have found the 2 supported files")
		req.Equal(uint64(2), monitoring.DirsScanned, "Should have scanned root + subdir")
		req.Equal(uint64(len(pdfContent)+len(pngContent)), monitoring.ScannerBytes, "Total size should match content length")

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
			observability.NewMonitoringManager(logger),
			nil, fileChan, nil,
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

// A worker both consumes dirChan and feeds it with the subdirectories it
// discovers. When the queue is full, it must keep making progress instead of
// waiting for room that only another (equally stuck) worker could free.
func TestFileScannerWorker_DoesNotDeadlockWhenDirQueueIsFull(t *testing.T) {
	req := require.New(t)

	const subDirs = 50
	root := t.TempDir()
	for i := 0; i < subDirs; i++ {
		req.NoError(os.Mkdir(filepath.Join(root, fmt.Sprintf("dir-%02d", i)), 0755))
	}

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	monitoring := observability.NewMonitoringManager(logger)

	// A queue far smaller than the number of subdirectories, and a single worker:
	// the worst case for the feedback loop.
	dirChan := make(chan string, 1)
	fileChan := make(chan *analyzer.FileAnalyzerRequest, 10)

	var scanWG sync.WaitGroup

	worker := NewFileScannerWorker(
		logger, "drive-123", monitoring,
		dirChan, fileChan,
		&scanWG,
		70, 90,
	)

	ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
	defer cancel()

	scanWG.Add(1)
	dirChan <- root

	go func() {
		_ = worker.Run(ctx)
	}()

	done := make(chan struct{})
	go func() {
		scanWG.Wait()
		close(done)
	}()

	select {
	case <-done:
	case <-ctx.Done():
		t.Fatal("scan did not complete: the worker is blocked on a full directory queue")
	}

	req.Equal(uint64(subDirs+1), monitoring.DirsScanned, "root and every subdirectory should be scanned")
}
