package workers

import (
	"chat-lab/domain/analyzer"
	"context"
	"log/slog"
)

// FileIngester receives the metadata of a scanned file and feeds it to the pipeline.
type FileIngester interface {
	Analyze(ctx context.Context, request analyzer.FileAnalyzerRequest) error
}

// FileIngestWorker links the scanner to the rest of the pipeline: it reads the
// files discovered by the scanner workers and submits them for storage and analysis.
// Submitting blocks when the pipeline is busy, which in turn fills fileChan and
// slows the scanner down.
type FileIngestWorker struct {
	log      *slog.Logger
	fileChan <-chan *analyzer.FileAnalyzerRequest
	ingester FileIngester
}

func NewFileIngestWorker(
	log *slog.Logger,
	fileChan <-chan *analyzer.FileAnalyzerRequest,
	ingester FileIngester,
) *FileIngestWorker {
	return &FileIngestWorker{log: log, fileChan: fileChan, ingester: ingester}
}

func (w *FileIngestWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			return ctx.Err()
		case req, ok := <-w.fileChan:
			if !ok {
				return nil
			}
			if req == nil {
				continue
			}
			if err := w.ingester.Analyze(ctx, *req); err != nil {
				if ctx.Err() != nil {
					return ctx.Err()
				}
				w.log.Warn("Scanned file rejected", "path", req.Path, "error", err)
			}
		}
	}
}
