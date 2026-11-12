// Package e2e runs the real Python specialists on real files.
//
// These tests need the Python environment created by ./scripts/setup_env.sh
// (a ./venv folder with the packages of requirements.txt). The audio test also
// needs the Whisper "base" model, which is downloaded the first time and then
// kept in the Hugging Face cache. When the environment is missing the tests are
// skipped, unless REQUIRE_SPECIALISTS=1 is set (as the CI does), in which case
// they fail.
//
// The audio fixture, test_data/speech_test.wav, was produced with:
//
//	espeak-ng -v en -s 140 -w test_data/speech_test.wav \
//	  "Please contact the accounting department before Friday."
package e2e

import (
	"chat-lab/domain"
	"chat-lab/domain/analyzer"
	"chat-lab/domain/event"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"chat-lab/internal"
	"chat-lab/observability"
	"chat-lab/runtime"
	"chat-lab/runtime/workers"
	"chat-lab/services"
	"context"
	"io"
	"log/slog"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	goruntime "runtime"
	"strings"
	"testing"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/dgraph-io/badger/v4"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

var projectRoot string

// TestMain moves to the project root: the coordinator starts the specialists
// from the current directory, with paths such as ./venv and ./services.
func TestMain(m *testing.M) {
	dir, err := os.Getwd()
	if err != nil {
		panic(err)
	}
	for {
		if _, err := os.Stat(filepath.Join(dir, "go.mod")); err == nil {
			break
		}
		parent := filepath.Dir(dir)
		if parent == dir {
			panic("go.mod not found: the e2e tests must run inside the project")
		}
		dir = parent
	}
	projectRoot = dir
	if err := os.Chdir(projectRoot); err != nil {
		panic(err)
	}
	os.Exit(m.Run())
}

func fixture(name string) string {
	return filepath.Join(projectRoot, "test_data", name)
}

func venvPython() string {
	if goruntime.GOOS == "windows" {
		return filepath.Join(projectRoot, "venv", "Scripts", "python.exe")
	}
	return filepath.Join(projectRoot, "venv", "bin", "python3")
}

// requirePython skips the test (or fails it when REQUIRE_SPECIALISTS=1) if the
// virtualenv lacks one of the Python modules a specialist needs.
func requirePython(t *testing.T, modules ...string) {
	t.Helper()
	fail := func(msg string) {
		t.Helper()
		if os.Getenv("REQUIRE_SPECIALISTS") == "1" {
			t.Fatal(msg)
		}
		t.Skip(msg + " (run ./scripts/setup_env.sh)")
	}
	if _, err := os.Stat(venvPython()); err != nil {
		fail("Python virtualenv not found at ./venv")
	}
	for _, module := range modules {
		if err := exec.Command(venvPython(), "-c", "import "+module).Run(); err != nil {
			fail("Python module " + module + " is not installed in ./venv")
		}
	}
}

func freePort(t *testing.T) int {
	t.Helper()
	listener, err := net.Listen("tcp", "localhost:0")
	require.NoError(t, err)
	defer listener.Close()
	return listener.Addr().(*net.TCPAddr).Port
}

var specialistConfigs = map[domain.Metric]struct {
	script       string
	capabilities []mimetypes.MIME
}{
	domain.MetricPDF: {"./services/pdf_specialist.py", []mimetypes.MIME{mimetypes.ApplicationPDF}},
	domain.MetricAudio: {"./services/audio_specialist.py", []mimetypes.MIME{
		mimetypes.AudioMPEG, mimetypes.AudioWAV, mimetypes.AudioXAIFF}},
	domain.MetricImage: {"./services/image_specialist.py", []mimetypes.MIME{
		mimetypes.ImagePNG, mimetypes.ImageJPEG, mimetypes.ImageGIF}},
}

// startSpecialists launches the given specialists, as the program does, and
// stops them when the test ends.
func startSpecialists(t *testing.T, logger *slog.Logger, processTrackerChan chan domain.Process, ids ...domain.Metric) *runtime.Coordinator {
	t.Helper()
	var configs []domain.Config
	for _, id := range ids {
		spec := specialistConfigs[id]
		configs = append(configs, domain.NewConfig(id, spec.script, "localhost", freePort(t), spec.capabilities))
	}

	appCtx, stop := context.WithCancel(context.Background())
	t.Cleanup(stop)
	// The first start of the audio specialist may download the Whisper model.
	bootCtx, cancelBoot := context.WithTimeout(appCtx, 4*time.Minute)
	t.Cleanup(cancelBoot)

	coordinator := runtime.NewCoordinator(logger, processTrackerChan, 100)
	grpcConfig := internal.GrpcConfig{
		MaxAttempts: 5, InitialBackoff: 100 * time.Millisecond,
		MaxBackoff: time.Second, BackoffMultiplier: 2,
	}
	require.NoError(t, coordinator.Init(appCtx, bootCtx, configs, "INFO", grpcConfig))
	return coordinator
}

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// analyze sends one file to the specialists able to handle it and returns the
// answer of the one specialist expected to respond.
func analyze(t *testing.T, coordinator *runtime.Coordinator, path string, mime mimetypes.MIME, expected domain.Metric) any {
	t.Helper()
	results, err := coordinator.Analyze(context.Background(), domain.SpecialistRequest{
		FileID: "e2e", Path: path, EffectiveMimeType: mime,
	})
	require.NoError(t, err)
	require.Len(t, results, 1, "exactly one specialist should handle %s", mime)
	result := results[0]
	require.NoError(t, result.SpecialistErr)
	require.Equal(t, expected, *result.ID)
	require.NotNil(t, result.Resp)
	return result.Resp.OneOf
}

func TestPDFSpecialist(t *testing.T) {
	requirePython(t, "fitz", "grpc")
	coordinator := startSpecialists(t, discardLogger(), make(chan domain.Process, 4), domain.MetricPDF)

	answer := analyze(t, coordinator, fixture("rapport_test.pdf"), mimetypes.ApplicationPDF, domain.MetricPDF)

	doc, ok := answer.(domain.DocumentData)
	require.True(t, ok, "the PDF specialist should answer with a document, got %T", answer)
	require.Equal(t, int32(1), doc.PageCount)
	require.Len(t, doc.Pages, 1)
	require.Contains(t, doc.Pages[0].Content, "Analyse PDF")
	require.Contains(t, doc.Pages[0].Content, "PyMuPDF")
	require.Contains(t, doc.Pages[0].Content, "généré", "accents must survive the extraction")
	require.Empty(t, doc.Author, "this PDF has no author")
}

func TestAudioSpecialist(t *testing.T) {
	requirePython(t, "faster_whisper", "grpc")
	coordinator := startSpecialists(t, discardLogger(), make(chan domain.Process, 4), domain.MetricAudio)

	answer := analyze(t, coordinator, fixture("speech_test.wav"), mimetypes.AudioWAV, domain.MetricAudio)

	audio, ok := answer.(domain.AudioData)
	require.True(t, ok, "the audio specialist should answer with audio data, got %T", answer)
	transcription := strings.ToLower(audio.Transcription)
	require.Contains(t, transcription, "accounting")
	require.Contains(t, transcription, "friday")
	require.Equal(t, "en", audio.Language)
	require.InDelta(t, 4.1, audio.Duration, 0.5)
}

func TestImageSpecialist(t *testing.T) {
	requirePython(t, "PIL", "grpc")
	coordinator := startSpecialists(t, discardLogger(), make(chan domain.Process, 4), domain.MetricImage)

	answer := analyze(t, coordinator, fixture("capture_test.png"), mimetypes.ImagePNG, domain.MetricImage)

	img, ok := answer.(domain.ImageData)
	require.True(t, ok, "the image specialist should answer with image data, got %T", answer)
	require.Equal(t, int32(800), img.Width)
	require.Equal(t, int32(600), img.Height)
	require.Equal(t, "PNG", img.Format)
	require.NotEmpty(t, img.Mode)
}

// TestAllFileTypesThroughThePipeline scans a folder holding a PDF, a recording
// and a picture (plus a text file that must be ignored) with the three real
// specialists, then checks that each file is stored with what its specialist
// extracted, and that it can be found by that content.
func TestAllFileTypesThroughThePipeline(t *testing.T) {
	requirePython(t, "fitz", "faster_whisper", "PIL", "grpc")
	logger := discardLogger()

	// A small tree, as a user's folder would look like.
	root := t.TempDir()
	for src, dst := range map[string]string{
		"rapport_test.pdf": "documents/rapport_test.pdf",
		"speech_test.wav":  "recordings/2024/speech_test.wav",
		"capture_test.png": "pictures/capture_test.png",
	} {
		data, err := os.ReadFile(fixture(src))
		require.NoError(t, err)
		target := filepath.Join(root, dst)
		require.NoError(t, os.MkdirAll(filepath.Dir(target), 0755))
		require.NoError(t, os.WriteFile(target, data, 0644))
	}
	require.NoError(t, os.WriteFile(filepath.Join(root, "notes.txt"), []byte("not a supported type"), 0644))

	db, err := badger.Open(badger.DefaultOptions("").WithInMemory(true).WithLogger(nil))
	require.NoError(t, err)
	defer func() { _ = db.Close() }()
	blugeWriter, err := bluge.OpenWriter(bluge.InMemoryOnlyConfig())
	require.NoError(t, err)
	defer func() { _ = blugeWriter.Close() }()

	config := internal.Config{
		BufferSize: 4, NumberOfWorkers: 2, ScannerWorkerNb: 2, AnalysisWorkerNb: 2,
		MetricInterval: 100 * time.Millisecond, BufferTimeout: 20 * time.Millisecond,
		SpecialistTimeout: time.Minute, RestartInterval: 50 * time.Millisecond,
		MaxAnalyzedEvent: 10, LowCapacityThreshold: 1, DriveID: "e2e-drive",
		ScanRootDir:                      root,
		ScannerBackpressureLowThreshold:  60,
		ScannerBackpressureHardThreshold: 85,
	}

	telemetryChan := make(chan event.Event, config.BufferSize)
	eventChan := make(chan event.Event, config.BufferSize)
	processTrackerChan := make(chan domain.Process, 8)
	coordinator := startSpecialists(t, logger, processTrackerChan,
		domain.MetricPDF, domain.MetricAudio, domain.MetricImage)

	repository := storage.NewAnalysisRepository(db, blugeWriter, logger, lo.ToPtr(50), 50)
	counter := analyzer.NewCountAnalyzedFiles()
	orchestrator := runtime.NewOrchestrator(
		logger, config, domain.NewGlobalMonitoring(),
		workers.NewSupervisor(logger, telemetryChan, config.RestartInterval),
		telemetryChan, eventChan, processTrackerChan,
		repository, coordinator,
		services.NewAnalyzerService(logger, repository, eventChan, &counter),
		observability.NewMonitoringManager(logger),
	)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	stopped := make(chan error, 1)
	go func() { stopped <- orchestrator.Start(ctx) }()

	select {
	case <-orchestrator.ScanDone():
	case <-time.After(30 * time.Second):
		t.Fatal("the scan did not complete")
	}

	// The analysis is asynchronous: wait until every file carries its extracted content.
	content := map[string]string{}
	require.Eventually(t, func() bool {
		analyses, _, err := repository.ScanAnalysesByRoom(config.DriveID, nil)
		if err != nil {
			return false
		}
		content = map[string]string{}
		for _, a := range analyses {
			if details, ok := a.Payload.(storage.FileDetails); ok && details.Content != "" {
				content[filepath.Base(details.Filename)] = details.Content
			}
		}
		return len(analyses) == 3 && len(content) == 3
	}, time.Minute, 100*time.Millisecond, "the three files should be stored with their extracted content")

	require.Contains(t, content["rapport_test.pdf"], "PyMuPDF")
	require.Contains(t, strings.ToLower(content["speech_test.wav"]), "accounting")
	require.Contains(t, content["capture_test.png"], "800x600")

	// Each file can be found by what was extracted from it, and only that file.
	for query, wantFile := range map[string]string{
		"pymupdf":    "rapport_test.pdf",
		"accounting": "speech_test.wav",
		"800x600":    "capture_test.png",
	} {
		var found []storage.Analysis
		require.Eventually(t, func() bool {
			var total uint64
			found, total, err = repository.SearchPaginated(context.Background(), query, config.DriveID, 0)
			return err == nil && total == 1
		}, 10*time.Second, 50*time.Millisecond, "searching %q should find exactly one file", query)
		require.True(t, strings.HasSuffix(found[0].Summary, wantFile),
			"searching %q found %s, expected %s", query, found[0].Summary, wantFile)
	}

	orchestrator.Stop()
	select {
	case err := <-stopped:
		require.NoError(t, err)
	case <-time.After(10 * time.Second):
		t.Fatal("the orchestrator did not stop")
	}
}
