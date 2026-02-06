package workers

import (
	"chat-lab/domain"
	"chat-lab/domain/event"
	"context"
	"log/slog"
	"sync"
	"time"

	_ "github.com/shirou/gopsutil"
	"github.com/shirou/gopsutil/process"
)

type HealthMonitoringWorker struct {
	mu                 sync.Mutex
	log                *slog.Logger
	telemetryChan      chan event.Event
	processTrackerChan chan domain.Process
	metricInterval     time.Duration
	processes          map[domain.PID]domain.Metric
}

func NewHealthMonitoringWorker(
	log *slog.Logger,
	telemetryChan chan event.Event,
	processTrackerChan chan domain.Process,
	metricInterval time.Duration,
) *HealthMonitoringWorker {
	return &HealthMonitoringWorker{
		log:                log,
		telemetryChan:      telemetryChan,
		processTrackerChan: processTrackerChan,
		metricInterval:     metricInterval,
		processes:          make(map[domain.PID]domain.Metric),
	}
}

func (w *HealthMonitoringWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.metricInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Context done, stopping technicalEvent send")
			return nil
		case <-ticker.C:
			for pid, metric := range w.processes {
				p, err := process.NewProcess(int32(pid))
				if err != nil {
					w.log.Debug("Error while retrieving process", "pid", pid, "err", err)
					w.mu.Lock()
					delete(w.processes, pid)
					w.mu.Unlock()
					w.log.Debug("Specialist has left the party", "pid", pid, "type", metric)
					continue
				}
				status, err := p.Status()
				if err != nil {
					w.log.Error("Error while finding process status", "err", err)
					continue
				}
				cpu, err := p.CPUPercent()
				if err != nil {
					w.log.Error("Error while finding process cpu usage", "err", err)
					continue
				}
				ram, err := p.MemoryPercent()
				if err != nil {
					w.log.Error("Error while finding process ram usage", "err", err)
					continue
				}
				select {
				case <-ctx.Done():
					return ctx.Err()
				case w.telemetryChan <- toProcessTrackerEvent(pid, metric, status, cpu, ram):
				default:
					w.log.Debug("Observability telemetry processTrackerChan lost")
				}
			}
		case proc := <-w.processTrackerChan:
			if _, err := process.NewProcess(int32(proc.PID)); err != nil {
				w.log.Debug("Error while retrieving process", "pid", proc.PID, "err", err)
			}
			// keep a track of process in map
			w.mu.Lock()
			w.processes[proc.PID] = proc.Metric
			w.mu.Unlock()
		}
	}
}

func toProcessTrackerEvent(pid domain.PID, metric domain.Metric,
	status string, cpu float64, ram float32, ) event.Event {
	return event.Event{
		Type:      event.PIDTrackerType,
		CreatedAt: time.Now().UTC(),
		Payload: event.ProcessTracker{
			Metric: metric,
			Status: domain.ToStatus(status),
			PID:    pid,
			Cpu:    cpu,
			Ram:    ram,
		},
	}
}
