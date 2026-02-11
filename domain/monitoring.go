package domain

import (
	"sync"
	"time"
)

type NodeType string
type NodeStatus string

const (
	MASTER     NodeType = "MASTER"
	SCANNER    NodeType = "SCANNER"
	SPECIALIST NodeType = "SPECIALIST"

	ALIVE NodeStatus = "ALIVE"
	DEAD  NodeStatus = "DEAD"
	GHOST NodeStatus = "GHOST"
)

type NodeHealth struct {
	ID              string
	Type            NodeType
	Status          NodeStatus
	PIDStatus       PIDStatus
	PID             int64
	CPU             float64
	RAM             uint64
	LastSeen        time.Time
	ItemsProcessed  uint64
	CurrentLoad     uint32
	QueueSize       uint32
	MaxCapacity     uint32
	CurrentItemName string
}

type GlobalMonitoring struct {
	mu    sync.RWMutex
	Nodes map[string]NodeHealth
}

func NewGlobalMonitoring() *GlobalMonitoring {
	return &GlobalMonitoring{
		Nodes: make(map[string]NodeHealth),
	}
}

func (m *GlobalMonitoring) UpdateNode(n NodeHealth) {
	m.mu.Lock()
	defer m.mu.Unlock()

	n.LastSeen = time.Now()
	n.Status = ALIVE
	m.Nodes[n.ID] = n
}

func (m *GlobalMonitoring) GetSnapshot() map[string]any {
	m.mu.RLock()
	defer m.mu.RUnlock()

	snapshot := make(map[string]any)
	for id, node := range m.Nodes {
		if time.Since(node.LastSeen) > 15*time.Second {
			node.Status = GHOST
		}
		snapshot[id] = node
	}
	return snapshot
}
