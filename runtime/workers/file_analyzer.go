package workers

import (
	"chat-lab/domain/analyzer"
	"chat-lab/sink"
	"context"
	"log/slog"
	"time"
)

type FileAnalyzerWorker struct {
	log            *slog.Logger
	metricInterval time.Duration
	fileScanChan   chan analyzer.FileAnalyzerRequest
	ana            sink.AnalysisSink
}

func NewFileAnalyzerWorker(
	log *slog.Logger,
	metricInterval time.Duration,
	fileScanChan chan analyzer.FileAnalyzerRequest,
) *FileAnalyzerWorker {
	return &FileAnalyzerWorker{
		log:            log,
		metricInterval: metricInterval,
		fileScanChan:   fileScanChan,
	}
}

func (w FileAnalyzerWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.metricInterval)
	defer ticker.Stop()

	for {
		select {
		case <-ctx.Done():
		case <-ticker.C:
			select {
			case <-ctx.Done():
			case fileScan := <-w.fileScanChan:

			}
		}
	}
}
