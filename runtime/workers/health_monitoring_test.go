package workers

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"io"
	"log/slog"
	"os"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// The health monitoring must report a specialist even when nobody reads the
// telemetry channel: the telemetry is best effort, the monitoring is not.
func TestHealthMonitoringWorker_ReportsSpecialistWhenTelemetryIsFull(t *testing.T) {
	req := require.New(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	globalMonitor := domain.NewGlobalMonitoring()
	telemetryChan := make(chan event.Event) // unbuffered and never read: always "full"
	processTrackerChan := make(chan domain.Process, 1)

	worker := NewHealthMonitoringWorker(logger, globalMonitor, telemetryChan, processTrackerChan, 10*time.Millisecond)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	done := make(chan struct{})
	go func() {
		defer close(done)
		_ = worker.Run(ctx)
	}()

	// The test process stands in for a specialist: it always exists.
	processTrackerChan <- domain.Process{PID: domain.PID(os.Getpid()), Metric: domain.MetricPDF}

	req.Eventually(func() bool {
		node, ok := globalMonitor.GetSnapshot()[string(domain.MetricPDF)].(domain.NodeHealth)
		return ok && node.Type == domain.SPECIALIST && node.PID == int64(os.Getpid())
	}, 3*time.Second, 10*time.Millisecond, "a specialist must appear in the monitoring even with a full telemetry channel")

	cancel()
	<-done
}
