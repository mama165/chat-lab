package event

import (
	"chat-lab/domain"
)

const (
	RestartedAfterPanicType Type = "WORKER_RESTARTED_AFTER_PANIC"
	ChannelCapacityType     Type = "CHANNEL_CAPACITY"
	PIDTrackerType          Type = "PID_TRACKER"
)

type WorkerRestartedAfterPanic struct {
	WorkerName string
}

type ChannelCapacity struct {
	ChannelName string
	Capacity    int
	Length      int
}

type ProcessTracker struct {
	PID    domain.PID
	Metric domain.Metric
	Status domain.PIDStatus
	Cpu    float64
	Ram    uint64
}
