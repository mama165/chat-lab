package domain

import (
	"sync"
	"sync/atomic"
)

type SpecialistHealth struct {
	CPU    float64
	RAM    float32
	Status string
	PID    PID
}

type GlobalMonitoring struct {
	TotalProcessed uint64
	ActiveScans    uint32
	MaxScans       uint32
	SpecialistsMu  sync.RWMutex
	Specialists    map[Metric]SpecialistHealth
}

func NewGlobalMonitoring(maxScans uint32) *GlobalMonitoring {
	return &GlobalMonitoring{
		TotalProcessed: 0,
		ActiveScans:    0,
		MaxScans:       maxScans,
		Specialists:    make(map[Metric]SpecialistHealth),
	}
}

func (m *GlobalMonitoring) IncActiveScans() {
	atomic.AddUint32(&m.ActiveScans, 1)
}

func (m *GlobalMonitoring) DecActiveScans() {
	atomic.AddUint32(&m.ActiveScans, ^uint32(0)) //  Useful to remove -1
}

func (m *GlobalMonitoring) AddProcessed() {
	atomic.AddUint64(&m.TotalProcessed, 1)
}

func (m *GlobalMonitoring) UpdateSpecialist(metric Metric, health SpecialistHealth) {
	m.SpecialistsMu.Lock()
	defer m.SpecialistsMu.Unlock()
	m.Specialists[metric] = health
}
