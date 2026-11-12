package storage

import (
	"chat-lab/domain/mimetypes"
	"context"
	"fmt"
	"testing"
	"time"

	"github.com/google/uuid"
	"github.com/mama165/sdk-go/database"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

func fileAnalysis(namespace, path, mime, content string) Analysis {
	id := uuid.New()
	return Analysis{
		ID:        id,
		EntityId:  id,
		Namespace: namespace,
		At:        time.Now(),
		Summary:   path,
		Payload:   FileDetails{Filename: path, MimeType: mime, Size: 42, Content: content},
	}
}

func newSearchRepo(t *testing.T, analyses ...Analysis) (*AnalysisRepository, context.Context) {
	t.Helper()
	ctx, log, badgerDB, blugeWriter, err := database.SetupBenchmark(t.TempDir())
	require.NoError(t, err)
	t.Cleanup(func() { database.CleanupDB(badgerDB, blugeWriter) })

	repo := NewAnalysisRepository(badgerDB, blugeWriter, log, lo.ToPtr(50), 50)
	require.NoError(t, repo.StoreBatch(analyses))
	require.NoError(t, repo.Flush())
	return repo, ctx
}

func paths(analyses []Analysis) []string {
	return lo.Map(analyses, func(a Analysis, _ int) string { return a.Payload.(FileDetails).Filename })
}

func TestSearchFiles_SpansEveryDriveByDefault(t *testing.T) {
	req := require.New(t)
	repo, ctx := newSearchRepo(t,
		fileAnalysis("laptop", "/docs/invoice.pdf", "application/pdf", "invoice for the roof repair"),
		fileAnalysis("usb-key", "/backup/roof.png", "image/png", "png image 800x600 RGB"),
		fileAnalysis("laptop", "/docs/recipe.pdf", "application/pdf", "apple pie"),
	)

	found, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "roof"})
	req.NoError(err)
	req.Equal(uint64(2), total)
	req.ElementsMatch([]string{"/docs/invoice.pdf", "/backup/roof.png"}, paths(found))

	// Limited to one drive.
	found, total, err = repo.SearchFiles(ctx, FileSearchQuery{Text: "roof", Namespace: "usb-key"})
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Equal([]string{"/backup/roof.png"}, paths(found))
}

func TestSearchFiles_EveryWordMustMatch(t *testing.T) {
	req := require.New(t)
	repo, ctx := newSearchRepo(t,
		fileAnalysis("d", "/a.pdf", "application/pdf", "invoice for the roof repair"),
		fileAnalysis("d", "/b.pdf", "application/pdf", "invoice for the car"),
	)

	found, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "invoice roof"})
	req.NoError(err)
	req.Equal(uint64(1), total)
	req.Equal([]string{"/a.pdf"}, paths(found))

	// Case does not matter.
	_, total, err = repo.SearchFiles(ctx, FileSearchQuery{Text: "INVOICE"})
	req.NoError(err)
	req.Equal(uint64(2), total)

	_, total, err = repo.SearchFiles(ctx, FileSearchQuery{Text: "boat"})
	req.NoError(err)
	req.Zero(total)
}

func TestSearchFiles_FiltersByKind(t *testing.T) {
	req := require.New(t)
	repo, ctx := newSearchRepo(t,
		fileAnalysis("d", "/holiday.pdf", "application/pdf", "holiday plan"),
		fileAnalysis("d", "/holiday.mp3", "audio/mpeg", "we talked about the holiday"),
		fileAnalysis("d", "/holiday.png", "image/png", "png image 800x600 RGB"),
	)

	for kind, want := range map[mimetypes.Kind]string{
		mimetypes.KindPDF:   "/holiday.pdf",
		mimetypes.KindAudio: "/holiday.mp3",
		mimetypes.KindImage: "/holiday.png",
	} {
		found, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "holiday", Kind: kind})
		req.NoError(err)
		req.Equal(uint64(1), total, "kind %s", kind)
		req.Equal([]string{want}, paths(found))
	}

	_, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "holiday"})
	req.NoError(err)
	req.Equal(uint64(3), total)
}

func TestSearchFiles_Pagination(t *testing.T) {
	req := require.New(t)
	var analyses []Analysis
	for i := 0; i < 7; i++ {
		analyses = append(analyses, fileAnalysis("d", fmt.Sprintf("/report-%d.pdf", i), "application/pdf", "quarterly report"))
	}
	repo, ctx := newSearchRepo(t, analyses...)

	seen := map[string]bool{}
	for page, wantLen := range []int{3, 3, 1} {
		found, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "quarterly", Offset: page * 3, Limit: 3})
		req.NoError(err)
		req.Equal(uint64(7), total, "the total counts every match, not only the page")
		req.Len(found, wantLen)
		for _, p := range paths(found) {
			req.False(seen[p], "%s returned on two pages", p)
			seen[p] = true
		}
	}
	req.Len(seen, 7)

	// Past the last page.
	found, total, err := repo.SearchFiles(ctx, FileSearchQuery{Text: "quarterly", Offset: 30, Limit: 3})
	req.NoError(err)
	req.Equal(uint64(7), total)
	req.Empty(found)
}
