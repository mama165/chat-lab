package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/infrastructure/storage"
	"chat-lab/internal"
	"chat-lab/observability"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/dgraph-io/badger/v4"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

// fakeCoordinator stands in for the Python specialists: it answers every PDF
// with a document whose title and text are derived from the file name.
type fakeCoordinator struct {
	mu       sync.Mutex
	analyzed []string
}

func (f *fakeCoordinator) Analyze(_ context.Context, req domain.SpecialistRequest) ([]domain.SpecialistResult, error) {
	f.mu.Lock()
	f.analyzed = append(f.analyzed, req.Path)
	f.mu.Unlock()

	name := strings.TrimSuffix(filepath.Base(req.Path), filepath.Ext(req.Path))
	pdf := domain.MetricPDF
	return []domain.SpecialistResult{{
		ID: &pdf,
		Resp: &domain.Response{OneOf: domain.DocumentData{
			Title: "Title of " + name,
			Pages: []domain.Page{{Number: 1, Content: "extracted text of " + name}},
		}},
	}}, nil
}

// TestPipeline_ScanStoreAnalyzeEnrich runs the whole pipeline in one process, with
// a real (in-memory) database and index, and only the specialists replaced:
// scan a folder -> store the file metadata -> analyze -> merge the results.
func TestPipeline_ScanStoreAnalyzeEnrich(t *testing.T) {
	req := require.New(t)

	// A small tree: two PDFs (one nested) and a text file the scanner must ignore.
	root := t.TempDir()
	pdfContent := []byte("%PDF-1.4 fake content")
	req.NoError(os.WriteFile(filepath.Join(root, "invoice.pdf"), pdfContent, 0644))
	req.NoError(os.MkdirAll(filepath.Join(root, "archive", "2024"), 0755))
	req.NoError(os.WriteFile(filepath.Join(root, "archive", "2024", "contract.pdf"), pdfContent, 0644))
	req.NoError(os.WriteFile(filepath.Join(root, "notes.txt"), []byte("plain text"), 0644))

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	db, err := badger.Open(badger.DefaultOptions("").WithInMemory(true).WithLogger(nil))
	req.NoError(err)
	defer func() { _ = db.Close() }()
	blugeWriter, err := bluge.OpenWriter(bluge.InMemoryOnlyConfig())
	req.NoError(err)
	defer func() { _ = blugeWriter.Close() }()

	config := internal.Config{
		BufferSize:                       2, // small queues: backpressure is exercised
		NumberOfWorkers:                  2,
		ScannerWorkerNb:                  2,
		AnalysisWorkerNb:                 2,
		MetricInterval:                   50 * time.Millisecond,
		BufferTimeout:                    20 * time.Millisecond,
		SpecialistTimeout:                time.Second,
		RestartInterval:                  50 * time.Millisecond,
		MaxAnalyzedEvent:                 10,
		LowCapacityThreshold:             1,
		DriveID:                          "test-drive",
		ScanRootDir:                      root,
		ScannerBackpressureLowThreshold:  60,
		ScannerBackpressureHardThreshold: 85,
	}

	telemetryChan := make(chan event.Event, config.BufferSize)
	eventChan := make(chan event.Event, config.BufferSize)
	processTrackerChan := make(chan domain.Process, config.BufferSize)
	supervisor := workers.NewSupervisor(logger, telemetryChan, config.RestartInterval)
	repository := storage.NewAnalysisRepository(db, blugeWriter, logger, lo.ToPtr(50), 50)
	counter := analyzer.NewCountAnalyzedFiles()
	analyzerService := services.NewAnalyzerService(logger, repository, eventChan, &counter)
	coordinator := &fakeCoordinator{}

	orchestrator := NewOrchestrator(
		logger, config, domain.NewGlobalMonitoring(), supervisor,
		telemetryChan, eventChan, processTrackerChan,
		repository, coordinator, analyzerService,
		observability.NewMonitoringManager(logger),
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stopped := make(chan error, 1)
	go func() { stopped <- orchestrator.Start(ctx) }()

	select {
	case <-orchestrator.ScanDone():
	case <-time.After(5 * time.Second):
		t.Fatal("the scan did not complete")
	}

	// The analysis is asynchronous: wait until both PDFs carry their specialist results.
	var analyses []storage.Analysis
	req.Eventually(func() bool {
		analyses, _, err = repository.ScanAnalysesByRoom(config.DriveID, nil)
		if err != nil || len(analyses) != 2 {
			return false
		}
		return lo.EveryBy(analyses, func(a storage.Analysis) bool {
			return strings.HasPrefix(a.Summary, "Title of ")
		})
	}, 5*time.Second, 20*time.Millisecond, "both PDFs should be stored and enriched")

	titles := lo.Map(analyses, func(a storage.Analysis, _ int) string { return a.Summary })
	req.ElementsMatch([]string{"Title of invoice", "Title of contract"}, titles)

	for _, a := range analyses {
		details, ok := a.Payload.(storage.FileDetails)
		req.True(ok, "payload should describe a file")
		req.True(strings.HasSuffix(details.Filename, ".pdf"))
		req.True(strings.HasPrefix(details.Content, "extracted text of "), "the specialist text should be stored")
		req.Equal(uint64(len(pdfContent)), details.Size)
	}

	// The text file was never sent to the specialists.
	coordinator.mu.Lock()
	analyzedPaths := append([]string(nil), coordinator.analyzed...)
	coordinator.mu.Unlock()
	req.Len(analyzedPaths, 2)
	for _, path := range analyzedPaths {
		req.True(strings.HasSuffix(path, ".pdf"))
		req.True(strings.HasPrefix(path, root), "files are read from their original location")
	}

	// The extracted text is searchable. The search index is updated right after the
	// database, so it can lag behind what was just read above: wait for it.
	var found []storage.Analysis
	req.Eventually(func() bool {
		var total uint64
		found, total, err = repository.SearchPaginated(context.Background(), "contract", config.DriveID, 0)
		return err == nil && total == 1 && len(found) == 1
	}, 5*time.Second, 20*time.Millisecond, "the text extracted from contract.pdf should be searchable")
	req.Equal("Title of contract", found[0].Summary)

	// Shutdown: every worker must exit once the orchestrator is stopped.
	orchestrator.Stop()
	select {
	case err := <-stopped:
		req.NoError(err)
	case <-time.After(5 * time.Second):
		t.Fatal("the orchestrator did not stop: a worker ignores cancellation")
	}
}

func TestOrchestrator_RejectsMissingScanFolder(t *testing.T) {
	req := require.New(t)
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))

	config := internal.Config{ScanRootDir: filepath.Join(t.TempDir(), "does-not-exist")}
	telemetryChan := make(chan event.Event, 1)
	orchestrator := NewOrchestrator(
		logger, config, domain.NewGlobalMonitoring(),
		workers.NewSupervisor(logger, telemetryChan, time.Second),
		telemetryChan, make(chan event.Event, 1), make(chan domain.Process, 1),
		nil, &fakeCoordinator{}, nil,
		observability.NewMonitoringManager(logger),
	)

	req.Error(orchestrator.Start(context.Background()))
}
