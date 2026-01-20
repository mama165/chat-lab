package services

import (
	"chat-lab/domain/analyzer"
	"log/slog"
)

type IAnalyzerService interface {
	Process(request analyzer.FileAnalyzerRequest) (analyzer.FileAnalyzerResponse, error)
}

type AnalyzerService struct {
	log *slog.Logger
}

func NewIngestionService(log *slog.Logger) *AnalyzerService {
	return &AnalyzerService{
		log: log,
	}
}

func (s AnalyzerService) Process(request analyzer.FileAnalyzerRequest) (analyzer.FileAnalyzerResponse, error) {
	return analyzer.FileAnalyzerResponse{}, nil
}
