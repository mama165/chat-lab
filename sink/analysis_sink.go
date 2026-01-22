package sink

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"context"
	"fmt"
	"log/slog"
	"sync"
)

type AnalysisSink struct {
	mu         sync.Mutex
	repository storage.IAnalysisRepository
	log        *slog.Logger
	minScoring float64
	maxScoring float64
	events     []event.FileAnalyse
}

func NewAnalysisSink(
	repository storage.IAnalysisRepository,
	log *slog.Logger,
	minScoring, maxScoring float64) *AnalysisSink {
	return &AnalysisSink{
		repository: repository,
		log:        log,
		minScoring: minScoring,
		maxScoring: maxScoring,
	}
}

func (a *AnalysisSink) Consume(_ context.Context, e contract.FileAnalyzerEvent) error {
	switch evt := e.(type) {
	case event.FileAnalyse:
		a.mu.Lock()
		defer a.mu.Unlock()
		a.events = append(a.events, evt)
		return nil
	default:
		a.log.Debug(fmt.Sprintf("Not implemented event : %v", evt))
		return nil
	}
}

func toAnalysis(_ event.SanitizedMessage) storage.Analysis {
	return storage.Analysis{}
}
