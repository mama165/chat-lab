package services

import "log/slog"

type IAnalyzerService interface {
	Process() error
}

type AnalyzerService struct {
	log *slog.Logger
}

func NewIngestionService(log *slog.Logger) *AnalyzerService {
	return &AnalyzerService{
		log: log,
	}
}

func (s AnalyzerService) Process() error {
	return nil
}
