package workers

import (
	"chat-lab/domain"
	"chat-lab/observability"
	pb "chat-lab/proto/monitoring"
	"context"
	"log/slog"
	"os"
	"time"

	"github.com/shirou/gopsutil/process"
	"google.golang.org/grpc"
)

type HeartbeatWorker struct {
	log        *slog.Logger
	grpcClient pb.MonitoringServiceClient
	monitoring *observability.MonitoringManager
}

func NewHeartbeatWorker(
	log *slog.Logger,
	conn *grpc.ClientConn,
	monitoring *observability.MonitoringManager,
) *HeartbeatWorker {
	return &HeartbeatWorker{
		log:        log,
		grpcClient: pb.NewMonitoringServiceClient(conn),
		monitoring: monitoring,
	}
}

// Run executes the main loop of the worker, sending health metrics (CPU, RAM, Status) every 5 seconds.
func (w *HeartbeatWorker) Run(ctx context.Context) error {
	w.log.Info("Starting scanner heartbeat worker")
	ticker := time.NewTicker(5 * time.Second)
	defer ticker.Stop()

	p, err := process.NewProcess(int32(os.Getpid()))
	if err != nil {
		return err
	}

	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-ticker.C:
			rss, cpu, status, err := getScannerSelfStats(p)
			if err != nil {
				w.log.Error("Failed to collect self stats", "err", err)
				continue
			}

			localStats := w.monitoring.GetLatest()

			_, err = w.grpcClient.ReportStatus(ctx, &pb.NodeStatus{
				NodeId:         "scanner-01",
				NodeType:       string(domain.SCANNER),
				Pid:            int64(os.Getpid()),
				PidStatus:      status,
				CpuPercent:     cpu,
				RamBytes:       rss,
				ItemsProcessed: localStats.FilesFound,
				QueueSize:      uint32(localStats.CurrentQueueSize),
				MaxCapacity:    localStats.MaxCapacity,
			})
			if err != nil {
				w.log.Warn("Master unreachable for heartbeat", "err", err)
			}
		}
	}
}

// getScannerSelfStats retrieves technical metrics (Memory, CPU, and OS Status) for the given process.
func getScannerSelfStats(p *process.Process) (uint64, float64, string, error) {
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
