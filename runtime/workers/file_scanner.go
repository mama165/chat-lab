package workers

import (
	"chat-lab/domain/analyzer"
	"context"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"

	"github.com/gabriel-vasile/mimetype"
)

// FileScannerWorker implements the contract.Worker interface to perform parallel
// filesystem traversal. It explores directories recursively by feeding subdirectories
// back into the dirChan and produces metadata requests for every regular file found.
type FileScannerWorker struct {
	log          *slog.Logger
	driveID      string
	countScanner *analyzer.CounterFileScanner
	// dirChan is a shared work queue used to distribute directory paths among
	// all active scanner workers, enabling concurrent tree walking.
	dirChan chan string
	// fileChan is the outbound pipeline where discovered file metadata is sent
	// as pointers to be consumed by the gRPC streaming sender.
	fileChan                         chan *analyzer.FileAnalyzerRequest
	scanWG                           *sync.WaitGroup
	workersWG                        *sync.WaitGroup
	scannerBackpressureLowThreshold  int
	scannerBackpressureHardThreshold int
}

func NewFileScannerWorker(
	log *slog.Logger,
	driveID string,
	countScanner *analyzer.CounterFileScanner,
	dirChan chan string,
	fileChan chan *analyzer.FileAnalyzerRequest,
	scanWG *sync.WaitGroup,
	workersWG *sync.WaitGroup,
	scannerBackpressureLowThreshold int,
	scannerBackpressureHardThreshold int,
) *FileScannerWorker {
	return &FileScannerWorker{
		log:                              log,
		driveID:                          driveID,
		countScanner:                     countScanner,
		dirChan:                          dirChan,
		fileChan:                         fileChan,
		scanWG:                           scanWG,
		workersWG:                        workersWG,
		scannerBackpressureLowThreshold:  scannerBackpressureLowThreshold,
		scannerBackpressureHardThreshold: scannerBackpressureHardThreshold,
	}
}

// Run executes the worker's main loop, consuming directory paths from dirChan and feeding subdirectories
// back into it while processing regular files. It exits when the context is canceled or dirChan is closed.
func (w *FileScannerWorker) Run(ctx context.Context) error {
	defer w.workersWG.Done()
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case currentDir, ok := <-w.dirChan:
			if !ok {
				return nil
			}
			// Checking pressure before each directory
			if err := w.throttle(ctx); err != nil {
				return err
			}
			w.handleDirectory(ctx, currentDir)
		}
	}
}

// throttle Use a select with time.After to ensure the worker remains
// responsive to context cancellation even during backpressure pauses.
func (w *FileScannerWorker) throttle(ctx context.Context) error {
	usage := len(w.fileChan)
	softLimit := buildBackPressureLimit(w.fileChan, w.scannerBackpressureLowThreshold)
	hardLimit := buildBackPressureLimit(w.fileChan, w.scannerBackpressureHardThreshold)

	var pause time.Duration
	if usage > hardLimit {
		pause = 200 * time.Millisecond
	} else if usage > softLimit {
		pause = 20 * time.Millisecond
	}

	if pause > 0 {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(pause):
		}
	}
	return nil
}

func (w *FileScannerWorker) handleDirectory(ctx context.Context, currentDir string) {
	defer w.scanWG.Done()

	entries, err := os.ReadDir(currentDir)
	if err != nil {
		w.countScanner.IncrErrorCount()
		w.log.Debug("Permission denied or path error", "path", currentDir, "error", err)
		return
	}

	w.countScanner.IncrDirsScanned()

	for _, entry := range entries {
		if entry.Type()&os.ModeSymlink != 0 {
			w.countScanner.IncrSkippedItems()
			continue
		}
		fullPath := filepath.Join(currentDir, entry.Name())
		if entry.IsDir() {
			w.scanWG.Add(1)
			select {
			case <-ctx.Done():
				return
			case w.dirChan <- fullPath:
			}
			continue
		}

		if entry.Type().IsRegular() {
			w.processFile(ctx, fullPath, entry)
		} else {
			w.countScanner.IncrSkippedItems()
		}
	}
}

func buildBackPressureLimit(fileChan chan *analyzer.FileAnalyzerRequest, limit int) int {
	return int(float64(cap(fileChan)) * float64(limit) / 100.0)
}

// processFile extracts metadata, detects MIME types via magic bytes sniffing,
// and pushes the resulting domain request into the outbound fileChan.
func (w *FileScannerWorker) processFile(ctx context.Context, path string, entry os.DirEntry) {
	info, err := entry.Info()
	if err != nil {
		w.countScanner.IncrErrorCount()
		return
	}

	// Sniffing the first 64 bytes (MagicBytes)
	magicBytes := make([]byte, 64)
	file, err := os.Open(path)
	if err == nil {
		n, _ := file.Read(magicBytes)
		file.Close()
		magicBytes = magicBytes[:n]
	} else {
		// If we can't open (locked file or permissions), we send an empty slice
		magicBytes = magicBytes[:0]
	}

	// Build the domain request
	req := &analyzer.FileAnalyzerRequest{
		Path:       path,
		DriveID:    w.driveID,
		Size:       uint64(info.Size()),
		MimeType:   mimetype.Detect(magicBytes).String(),
		MagicBytes: magicBytes,
		ScannedAt:  time.Now(),
		SourceType: analyzer.LOCALFIXED,
	}

	select {
	case <-ctx.Done():
	case w.fileChan <- req:
		w.countScanner.IncrFilesScanned()
		w.countScanner.IncrSizeFound(req.Size)
	}
}
