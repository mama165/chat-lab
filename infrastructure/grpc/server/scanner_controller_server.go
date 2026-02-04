package server

import (
	"chat-lab/domain"
	pb "chat-lab/proto/analyzer"
	"chat-lab/services"
	"context"
)

type ScannerControllerServer struct {
	pb.ScannerControllerServer
	service services.ScannerControlService
}

func NewScannerControllerServer(service services.ScannerControlService) *ScannerControllerServer {
	return &ScannerControllerServer{
		service: service,
	}
}

func (s *ScannerControllerServer) TriggerScan(_ context.Context, req *pb.ScanRequest) (*pb.ScanResponse, error) {
	response := s.service.TriggerScan(fromScanRequestPb(req))
	return toPbScanResponse(response), nil
}

func fromScanRequestPb(req *pb.ScanRequest) domain.ScanRequest {
	return domain.ScanRequest{
		Path: req.Path,
	}
}

func toPbScanResponse(resp domain.ScanResponse) *pb.ScanResponse {
	return &pb.ScanResponse{Started: resp.Started}
}
