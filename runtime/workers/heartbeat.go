package workers

import (
	"chat-lab/domain"
	"chat-lab/observability"
	"context"
	"log/slog"
	"os"
	"time"

	"github.com/shirou/gopsutil/process"
)

// HeartbeatWorker periodically reports the health of this process (CPU, RAM,
// status) and the scan progress to the global monitoring, next to the specialists.
type HeartbeatWorker struct {
	log           *slog.Logger
	globalMonitor *domain.GlobalMonitoring
	monitoring    *observability.MonitoringManager
	interval      time.Duration
}

func NewHeartbeatWorker(
	log *slog.Logger,
	globalMonitor *domain.GlobalMonitoring,
	monitoring *observability.MonitoringManager,
	interval time.Duration,
) *HeartbeatWorker {
	return &HeartbeatWorker{
		log:           log,
		globalMonitor: globalMonitor,
		monitoring:    monitoring,
		interval:      interval,
	}
}

func (w *HeartbeatWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.interval)
	defer ticker.Stop()

	pid := os.Getpid()
	p, err := process.NewProcess(int32(pid))
	if err != nil {
		return err
	}

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			rss, cpu, status, err := getSelfStats(p)
			if err != nil {
				w.log.Error("Failed to collect self stats", "err", err)
				continue
			}

			stats := w.monitoring.GetLatest()
			w.globalMonitor.UpdateNode(domain.NodeHealth{
				ID:             "master",
				Type:           domain.MASTER,
				PIDStatus:      domain.ToPIDStatus(status),
				PID:            int64(pid),
				CPU:            cpu,
				RAM:            rss,
				ItemsProcessed: stats.FilesFound,
				QueueSize:      uint32(stats.CurrentQueueSize),
				MaxCapacity:    stats.MaxCapacity,
			})
		}
	}
}

// getSelfStats retrieves technical metrics (Memory, CPU, and OS Status) for the given process.
func getSelfStats(p *process.Process) (uint64, float64, string, error) {
	memInfo, err := p.MemoryInfo()
	if err != nil {
		return 0, 0, "", err
	}

	cpuPercent, err := p.CPUPercent()
	if err != nil {
		return 0, 0, "", err
	}

	status, err := p.Status()
	if err != nil {
		return 0, 0, "", err
	}
	return memInfo.RSS, cpuPercent, status, nil
}
