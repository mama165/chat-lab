package services

import (
	"chat-lab/domain"
	"log/slog"
	"sync"
)

type ScannerControlService struct {
	dirChan chan<- string
	scanWG  *sync.WaitGroup
	log     *slog.Logger
}

func NewScannerControlService(
	dirChan chan<- string,
	scanWG *sync.WaitGroup,
	log *slog.Logger) ScannerControlService {
	return ScannerControlService{
		dirChan: dirChan,
		scanWG:  scanWG,
		log:     log,
	}
}

func (s ScannerControlService) TriggerScan(req domain.ScanRequest) domain.ScanResponse {
	s.log.Info("Triggering manual scan", "path", req.Path)
	s.scanWG.Add(1)
	s.dirChan <- req.Path

	return domain.ScanResponse{
		Started: true,
	}
}
