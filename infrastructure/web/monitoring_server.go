// Package web exposes the monitoring API and the dashboard pages over HTTP.
package web

import (
	"chat-lab/domain"
	"chat-lab/observability"
	"encoding/json"
	"fmt"
	"net/http"
)

type MonitoringServer struct {
	monitor *domain.GlobalMonitoring
	stats   *observability.MonitoringManager
}

func NewMonitoringServer(monitor *domain.GlobalMonitoring, stats *observability.MonitoringManager) *MonitoringServer {
	return &MonitoringServer{monitor: monitor, stats: stats}
}

// Start serves, until the process stops:
//   - /api/monitoring: health of this process and of each specialist
//   - /api/stats: scan progress and throughput
//   - /: the dashboard pages of the ui folder
func (s *MonitoringServer) Start(port int) error {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/monitoring", s.handleMonitoring)
	mux.HandleFunc("/api/stats", s.handleStats)
	mux.Handle("/", http.FileServer(http.Dir("./ui")))

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", port),
		Handler: mux,
	}
	return server.ListenAndServe()
}

func (s *MonitoringServer) handleMonitoring(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, s.monitor.GetSnapshot())
}

func (s *MonitoringServer) handleStats(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, s.stats.GetLatest())
}

func writeJSON(w http.ResponseWriter, payload any) {
	w.Header().Set("Content-Type", "application/json")
	if err := json.NewEncoder(w).Encode(payload); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
	}
}
