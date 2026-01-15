package sink

import (
	"chat-lab/domain/event"
	"chat-lab/repositories"
	"context"
	"fmt"
	"log/slog"
)

type AnalysisSink struct {
	repository repositories.IAnalysisRepository
	log        *slog.Logger
	minScoring float64
	maxScoring float64
}

func NewAnalysisSink(repository repositories.IAnalysisRepository,
	log *slog.Logger,
	minScoring float64,
	maxScoring float64) *AnalysisSink {
	return &AnalysisSink{
		repository: repository,
		log:        log,
		minScoring: minScoring,
		maxScoring: maxScoring,
	}
}

func (a AnalysisSink) Consume(_ context.Context, e event.DomainEvent) error {
	switch evt := e.(type) {
	case event.SanitizedMessage:
		if evt.ToxicityScore > a.minScoring && evt.ToxicityScore < a.maxScoring {
			a.log.Debug("Toxicity score kept for analysis", "score", evt.ToxicityScore)
			return a.repository.Store(toAnalysis(evt))
		}
		return nil
	default:
		a.log.Debug(fmt.Sprintf("Not implemented event : %v", evt))
		return nil
	}
}

func toAnalysis(event event.SanitizedMessage) repositories.Analysis {
	return repositories.Analysis{
		Score: event.ToxicityScore,
	}
}
