package web

import (
	"chat-lab/domain"
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"chat-lab/observability"
	"context"
	"encoding/json"
	"errors"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/blugelabs/bluge"
	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/samber/lo"
	"github.com/stretchr/testify/require"
)

// fakeSearcher records the query it receives and answers with canned results.
type fakeSearcher struct {
	got     storage.FileSearchQuery
	calls   int
	results []storage.Analysis
	total   uint64
	err     error
}

func (f *fakeSearcher) SearchFiles(_ context.Context, query storage.FileSearchQuery) ([]storage.Analysis, uint64, error) {
	f.got = query
	f.calls++
	return f.results, f.total, f.err
}

func newTestServer(searcher FileSearcher) http.Handler {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	return NewServer(domain.NewGlobalMonitoring(), observability.NewMonitoringManager(logger), searcher).Handler()
}

func get(t *testing.T, handler http.Handler, target string) (int, searchResponse, map[string]string) {
	t.Helper()
	recorder := httptest.NewRecorder()
	handler.ServeHTTP(recorder, httptest.NewRequest(http.MethodGet, target, nil))

	var ok searchResponse
	var failure map[string]string
	if recorder.Code == http.StatusOK {
		require.NoError(t, json.Unmarshal(recorder.Body.Bytes(), &ok))
	} else {
		require.NoError(t, json.Unmarshal(recorder.Body.Bytes(), &failure))
	}
	require.Equal(t, "application/json; charset=utf-8", recorder.Header().Get("Content-Type"))
	return recorder.Code, ok, failure
}

func TestSearch_ReturnsResultsWithExcerpt(t *testing.T) {
	req := require.New(t)
	id := uuid.New()
	scannedAt := time.Date(2026, 3, 1, 10, 0, 0, 0, time.UTC)
	searcher := &fakeSearcher{
		total: 1,
		results: []storage.Analysis{{
			EntityId:  id,
			Namespace: "laptop",
			At:        scannedAt,
			Summary:   "Roof repair invoice",
			Payload: storage.FileDetails{
				Filename: `C:\Users\me\Documents\invoice.pdf`,
				MimeType: "application/pdf",
				Size:     1402,
				Content:  "Dear customer,\nplease find the invoice for the roof repair.\nBest regards",
			},
		}},
	}

	status, body, _ := get(t, newTestServer(searcher), "/api/search?q=Roof")

	req.Equal(http.StatusOK, status)
	req.Equal(storage.FileSearchQuery{Text: "Roof", Offset: 0, Limit: 20}, searcher.got)
	req.Equal("Roof", body.Query)
	req.Equal(1, body.Page)
	req.Equal(20, body.PageSize)
	req.Equal(uint64(1), body.Total)
	req.Len(body.Results, 1)

	result := body.Results[0]
	req.Equal(id.String(), result.ID)
	req.Equal(`C:\Users\me\Documents\invoice.pdf`, result.Path)
	req.Equal("invoice.pdf", result.Name, "the name is the last element of a Windows path too")
	req.Equal("pdf", result.Type)
	req.Equal("application/pdf", result.MimeType)
	req.Equal(uint64(1402), result.Size)
	req.Equal("Roof repair invoice", result.Title)
	req.Equal("laptop", result.Drive)
	req.True(scannedAt.Equal(result.ScannedAt))
	req.Equal("Dear customer, please find the invoice for the roof repair. Best regards", result.Excerpt)
}

func TestSearch_PassesTypeAndPagination(t *testing.T) {
	req := require.New(t)
	searcher := &fakeSearcher{}

	status, body, _ := get(t, newTestServer(searcher), "/api/search?q=holiday+plan&type=audio&page=3&size=10")

	req.Equal(http.StatusOK, status)
	req.Equal(storage.FileSearchQuery{Text: "holiday plan", Kind: mimetypes.KindAudio, Offset: 20, Limit: 10}, searcher.got)
	req.Equal("audio", body.Type)
	req.Equal(3, body.Page)
	req.Equal(10, body.PageSize)
	req.NotNil(body.Results, "no result is an empty list, not null")
	req.Empty(body.Results)
}

func TestSearch_RejectsInvalidRequests(t *testing.T) {
	cases := map[string]string{
		"/api/search":                               "parameter q is required",
		"/api/search?q=+++":                         "parameter q is required",
		"/api/search?q=a&type=video":                "parameter type must be pdf, audio or image",
		"/api/search?q=a&page=0":                    "parameter page must be a positive number",
		"/api/search?q=a&page=abc":                  "parameter page must be a positive number",
		"/api/search?q=a&size=0":                    "parameter size must be between 1 and 100",
		"/api/search?q=a&size=101":                  "parameter size must be between 1 and 100",
		"/api/search?q=" + strings.Repeat("a", 257): "parameter q is too long (256 characters at most)",
	}
	for target, want := range cases {
		searcher := &fakeSearcher{}
		status, _, failure := get(t, newTestServer(searcher), target)
		require.Equal(t, http.StatusBadRequest, status, target)
		require.Equal(t, want, failure["error"], target)
		require.Zero(t, searcher.calls, "an invalid request must not reach the index: %s", target)
	}
}

func TestSearch_OnlyAcceptsGet(t *testing.T) {
	recorder := httptest.NewRecorder()
	newTestServer(&fakeSearcher{}).ServeHTTP(recorder, httptest.NewRequest(http.MethodPost, "/api/search?q=a", nil))
	require.Equal(t, http.StatusMethodNotAllowed, recorder.Code)
	require.Equal(t, http.MethodGet, recorder.Header().Get("Allow"))
}

func TestSearch_HidesInternalErrors(t *testing.T) {
	status, _, failure := get(t, newTestServer(&fakeSearcher{err: errors.New("badger: disk I/O error at /secret/path")}), "/api/search?q=a")
	require.Equal(t, http.StatusInternalServerError, status)
	require.Equal(t, "search failed", failure["error"])
}

func TestExcerpt(t *testing.T) {
	long := strings.Repeat("lorem ipsum ", 40) + "the NEEDLE is here " + strings.Repeat("dolor sit ", 40)

	t.Run("centered on the first matching word, case-insensitive", func(t *testing.T) {
		got := excerpt(long, []string{"needle"})
		require.Contains(t, got, "the NEEDLE is here")
		require.True(t, strings.HasPrefix(got, "…"))
		require.True(t, strings.HasSuffix(got, "…"))
		require.LessOrEqual(t, len([]rune(got)), 2*excerptRadius+len("needle")+2)
	})

	t.Run("beginning of the content when the match is elsewhere", func(t *testing.T) {
		got := excerpt(long, []string{"absent"})
		require.True(t, strings.HasPrefix(got, "lorem ipsum"))
		require.True(t, strings.HasSuffix(got, "…"))
	})

	t.Run("short content is returned whole", func(t *testing.T) {
		require.Equal(t, "png image 800x600 RGB", excerpt("png image 800x600 RGB", []string{"800x600"}))
	})

	t.Run("accented text is cut on characters, not bytes", func(t *testing.T) {
		got := excerpt(strings.Repeat("é", 300)+" généré "+strings.Repeat("à", 300), []string{"généré"})
		require.Contains(t, got, "généré")
		require.True(t, strings.ToValidUTF8(got, "?") == got)
	})

	t.Run("empty content", func(t *testing.T) {
		require.Empty(t, excerpt("  \n ", []string{"a"}))
	})
}

// TestSearch_AgainstTheRealIndex goes through HTTP, the handler, the search
// engine and the database, with nothing replaced.
func TestSearch_AgainstTheRealIndex(t *testing.T) {
	req := require.New(t)

	db, err := badger.Open(badger.DefaultOptions("").WithInMemory(true).WithLogger(nil))
	req.NoError(err)
	defer func() { _ = db.Close() }()
	writer, err := bluge.OpenWriter(bluge.InMemoryOnlyConfig())
	req.NoError(err)
	defer func() { _ = writer.Close() }()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	repository := storage.NewAnalysisRepository(db, writer, logger, lo.ToPtr(50), 50)

	file := func(drive, path, mime, content string) storage.Analysis {
		id := uuid.New()
		return storage.Analysis{
			ID: id, EntityId: id, Namespace: drive, At: time.Now(), Summary: path,
			Payload: storage.FileDetails{Filename: path, MimeType: mime, Size: 10, Content: content},
		}
	}
	req.NoError(repository.StoreBatch([]storage.Analysis{
		file("laptop", "/docs/roof_invoice.pdf", "application/pdf", "Invoice for the roof repair, due in March."),
		file("laptop", "/audio/meeting.mp3", "audio/mpeg", "We agreed to repair the roof before winter."),
		file("usb", "/photos/garden.png", "image/png", "png image 800x600 RGB"),
	}))

	server := httptest.NewServer(newTestServer(repository))
	defer server.Close()

	search := func(query string) searchResponse {
		resp, err := http.Get(server.URL + "/api/search?" + query)
		req.NoError(err)
		defer resp.Body.Close()
		req.Equal(http.StatusOK, resp.StatusCode)
		var body searchResponse
		req.NoError(json.NewDecoder(resp.Body).Decode(&body))
		return body
	}
	names := func(body searchResponse) []string {
		return lo.Map(body.Results, func(r searchResult, _ int) string { return r.Name })
	}

	// A word present in the content of two files, on one drive.
	body := search("q=roof")
	req.Equal(uint64(2), body.Total)
	req.ElementsMatch([]string{"roof_invoice.pdf", "meeting.mp3"}, names(body))

	// Every word must match.
	body = search("q=" + url.QueryEscape("roof winter"))
	req.Equal([]string{"meeting.mp3"}, names(body))
	req.Contains(body.Results[0].Excerpt, "roof before winter")
	req.Equal("audio", body.Results[0].Type)

	// Limited to one kind of file.
	req.Equal([]string{"roof_invoice.pdf"}, names(search("q=roof&type=pdf")))

	// Found by a part of its name only, on another drive.
	body = search("q=garden")
	req.Equal([]string{"garden.png"}, names(body))
	req.Equal("usb", body.Results[0].Drive)

	// Nothing matches.
	body = search("q=submarine")
	req.Zero(body.Total)
	req.Empty(body.Results)
}
