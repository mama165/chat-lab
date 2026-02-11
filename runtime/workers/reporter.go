package workers

import (
	"chat-lab/observability"
	"context"
	"fmt"
	"sync"
	"time"
)

type ReporterWorker struct {
	monitoring *observability.MonitoringManager
	interval   time.Duration
	workersWG  *sync.WaitGroup
}

// Run starts the reporting loop to display real-time metrics until context cancellation
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

// printStats formats and prints the latest metrics snapshot to the console
func (w *ReporterWorker) printStats(startTime time.Time) {
	stats := w.monitoring.GetLatest()
	duration := time.Since(startTime).Round(time.Second).String()

	fmt.Printf("\r📊 [%s] RAM: %dMB | Disk: %.2f MB/s | Net: %.2f MB/s | Files: %d | Queue: %d",
		duration,
		stats.AllocMemMb,
		stats.ScannerDiskSpeed,
		stats.MasterNetSpeed,
		stats.FilesFound,
		stats.CurrentQueueSize,
	)
}
