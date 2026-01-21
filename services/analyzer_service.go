package services

import (
	"chat-lab/domain/analyzer"
	"chat-lab/infrastructure/storage"
	"log/slog"

	"github.com/go-playground/validator/v10"
)

type IAnalyzerService interface {
	Analyze(request analyzer.FileAnalyzerRequest) (analyzer.FileAnalyzerResponse, error)
}

type AnalyzerService struct {
	log        *slog.Logger
	validator  *validator.Validate
	repository storage.IAnalysisRepository
}

func NewAnalyzerService(log *slog.Logger, repository storage.IAnalysisRepository) *AnalyzerService {
	return &AnalyzerService{
		log:        log,
		repository: repository,
		validator:  validator.New(),
	}
}

func (s AnalyzerService) Analyze(request analyzer.FileAnalyzerRequest) (analyzer.FileAnalyzerResponse, error) {
	if err := s.validator.Struct(request); err != nil {
		return analyzer.FileAnalyzerResponse{}, err
	}
	return analyzer.FileAnalyzerResponse{}, nil
}
