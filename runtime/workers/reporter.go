package workers

import (
	"chat-lab/domain/analyzer"
	"context"
	"fmt"
	"sync"
	"time"
)

type ReporterWorker struct {
	counter   *analyzer.CounterFileScanner
	interval  time.Duration
	workersWG *sync.WaitGroup
}

func NewReporterWorker(counter *analyzer.CounterFileScanner,
	interval time.Duration, workersWG *sync.WaitGroup) *ReporterWorker {
	return &ReporterWorker{
		counter:   counter,
		interval:  interval,
		workersWG: workersWG,
	}
}

// Run starts the reporting loop. It ticks at a regular interval to display
// real-time scanning metrics until the context is cancelled.
func (w *ReporterWorker) Run(ctx context.Context) error {
	defer w.workersWG.Done()
	startTime := time.Now()
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
			w.printStats(startTime)
			fmt.Println("\n🏁 Reporter stopped.")
			return ctx.Err()
		case <-ticker.C:
			w.printStats(startTime)
		}
	}
}

// printStats calculates and prints the current progress metrics to the console.
// It uses the carriage return character (\r) to overwrite the current line,
// providing a dynamic dashboard view without polluting the terminal output.
func (w *ReporterWorker) printStats(startTime time.Time) {
	c := w.counter
	files := c.FilesScanned
	dirs := c.DirsScanned
	bytes := c.BytesProcessed
	errs := c.ErrorCount
	skipped := c.SkippedItems

	duration := time.Since(startTime)

	// Truncate milliseconds for cleaning display
	durationStr := duration.Round(time.Second).String()

	elapsed := duration.Seconds()
	speed := float64(files) / elapsed
	gb := float64(bytes) / (1024 * 1024 * 1024)

	fmt.Printf("\r🚀 Time: %s | Dirs: %d | Files: %d | Speed: %.0f f/s | Data: %.2f GB | Skipped: %d | ⚠️ Errs: %d",
		durationStr, dirs, files, speed, gb, skipped, errs)
}
