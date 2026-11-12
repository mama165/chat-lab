package runtime

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/grpc/client"
	"context"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// newCoordinatorWithPDFSpecialist returns a coordinator that knows one PDF
// specialist. The specialist has no gRPC client: the tests below must be
// rejected before any call is attempted.
func newCoordinatorWithPDFSpecialist(maxFileSizeMB int) *Coordinator {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	coordinator := NewCoordinator(logger, make(chan domain.Process, 1), maxFileSizeMB)
	coordinator.Add(client.NewSpecialistClient(
		domain.MetricPDF, nil, nil, 0, time.Now(),
		[]mimetypes.MIME{mimetypes.ApplicationPDF},
	))
	return coordinator
}

func TestCoordinator_Analyze_NoSpecialistForType(t *testing.T) {
	req := require.New(t)
	coordinator := newCoordinatorWithPDFSpecialist(1)

	results, err := coordinator.Analyze(context.Background(), domain.SpecialistRequest{
		FileID:            "file-1",
		Path:              filepath.Join(t.TempDir(), "song.mp3"),
		EffectiveMimeType: mimetypes.AudioMPEG,
	})
	req.NoError(err)
	req.Empty(results)
}

func TestCoordinator_Analyze_RejectsWhatIsNotARegularFile(t *testing.T) {
	req := require.New(t)
	coordinator := newCoordinatorWithPDFSpecialist(1)

	// A directory standing where a PDF was scanned.
	dir := filepath.Join(t.TempDir(), "report.pdf")
	req.NoError(os.Mkdir(dir, 0755))

	_, err := coordinator.Analyze(context.Background(), domain.SpecialistRequest{
		FileID:            "file-1",
		Path:              dir,
		EffectiveMimeType: mimetypes.ApplicationPDF,
	})
	req.ErrorContains(err, "not a regular file")
}

func TestCoordinator_Analyze_RejectsMissingAndOversizedFiles(t *testing.T) {
	req := require.New(t)
	coordinator := newCoordinatorWithPDFSpecialist(1) // 1 MB limit

	_, err := coordinator.Analyze(context.Background(), domain.SpecialistRequest{
		FileID:            "file-1",
		Path:              filepath.Join(t.TempDir(), "missing.pdf"),
		EffectiveMimeType: mimetypes.ApplicationPDF,
	})
	req.ErrorContains(err, "file not found")

	big := filepath.Join(t.TempDir(), "big.pdf")
	req.NoError(os.WriteFile(big, make([]byte, 2*domain.MB), 0644))
	_, err = coordinator.Analyze(context.Background(), domain.SpecialistRequest{
		FileID:            "file-2",
		Path:              big,
		EffectiveMimeType: mimetypes.ApplicationPDF,
	})
	req.ErrorContains(err, "too large")
}
