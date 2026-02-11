package server

import (
	"chat-lab/domain"
	pb "chat-lab/proto/monitoring"
	"context"
	"encoding/json"
	"fmt"
	"net/http"

	"google.golang.org/protobuf/types/known/emptypb"
)

type MonitoringServer struct {
	pb.UnimplementedMonitoringServiceServer
	monitor *domain.GlobalMonitoring
}

func NewMonitoringServer(m *domain.GlobalMonitoring) *MonitoringServer {
	return &MonitoringServer{monitor: m}
}

func (s *MonitoringServer) ReportStatus(_ context.Context, req *pb.NodeStatus) (*emptypb.Empty, error) {
	s.monitor.UpdateNode(domain.NodeHealth{
		ID:              req.NodeId,
		Type:            domain.NodeType(req.NodeType),
		PIDStatus:       domain.ToPIDStatus(req.PidStatus),
		PID:             req.Pid,
		CPU:             req.CpuPercent,
		RAM:             req.RamBytes,
		ItemsProcessed:  req.ItemsProcessed,
		CurrentLoad:     req.CurrentLoad,
		QueueSize:       req.QueueSize,
		MaxCapacity:     req.MaxCapacity,
		CurrentItemName: req.CurrentItemName,
	})

	return &emptypb.Empty{}, nil
}

func (s *MonitoringServer) Start(port int) error {
	mux := http.NewServeMux()

	// 1. Ton API JSON (déjà là)
	mux.HandleFunc("/api/monitoring", s.handleMonitoring)

	// 2. Ton Interface HTML (à activer)
	// Cette ligne dit : "Sers tout ce qui est dans le dossier /ui"
	fileServer := http.FileServer(http.Dir("./ui"))
	mux.Handle("/", fileServer)

	server := &http.Server{
		Addr:    fmt.Sprintf(":%d", port),
		Handler: mux,
	}

	fmt.Printf("🌐 Interface disponible sur : http://localhost:%d/monitoring.html\n", port)
	return server.ListenAndServe()
}

func (s *MonitoringServer) handleMonitoring(w http.ResponseWriter, _ *http.Request) {
	w.Header().Set("Content-Type", "application/json")

	// On récupère le snapshot que tu as codé dans domain/GlobalMonitoring
	snapshot := s.monitor.GetSnapshot()

	json.NewEncoder(w).Encode(snapshot)
}
