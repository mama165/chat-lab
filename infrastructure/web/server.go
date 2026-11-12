// Package web exposes the search API, the monitoring API and the dashboard pages over HTTP.
package web

import (
	"chat-lab/domain"
	"chat-lab/observability"
	"encoding/json"
	"net/http"
)

type Server struct {
	monitor  *domain.GlobalMonitoring
	stats    *observability.MonitoringManager
	searcher FileSearcher
}

func NewServer(monitor *domain.GlobalMonitoring, stats *observability.MonitoringManager, searcher FileSearcher) *Server {
	return &Server{monitor: monitor, stats: stats, searcher: searcher}
}

// Handler routes:
//   - /api/search: search the indexed files
//   - /api/monitoring: health of this process and of each specialist
//   - /api/stats: scan progress and throughput
//   - /: the dashboard pages of the ui folder
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("/api/search", s.handleSearch)
	mux.HandleFunc("/api/monitoring", s.handleMonitoring)
	mux.HandleFunc("/api/stats", s.handleStats)
	mux.Handle("/", http.FileServer(http.Dir("./ui")))
	return mux
}

// Start serves on the given address (host:port) until the process stops.
func (s *Server) Start(addr string) error {
	server := &http.Server{Addr: addr, Handler: s.Handler()}
	return server.ListenAndServe()
}

func (s *Server) handleMonitoring(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.monitor.GetSnapshot())
}

func (s *Server) handleStats(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, s.stats.GetLatest())
}

func writeJSON(w http.ResponseWriter, status int, payload any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	// The status line is already sent: nothing more can be reported to the client.
	_ = json.NewEncoder(w).Encode(payload)
}

func writeError(w http.ResponseWriter, status int, message string) {
	writeJSON(w, status, map[string]string{"error": message})
}
