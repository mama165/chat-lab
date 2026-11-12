package web

import (
	"chat-lab/domain/mimetypes"
	"chat-lab/infrastructure/storage"
	"context"
	"net/http"
	"strconv"
	"strings"
	"time"
	"unicode"
	"unicode/utf8"
)

const (
	defaultPageSize = 20
	maxPageSize     = 100
	maxQueryLength  = 256
	// excerptRadius is the number of characters kept on each side of the match.
	excerptRadius = 80
)

// FileSearcher is the part of the storage the search API needs.
type FileSearcher interface {
	SearchFiles(ctx context.Context, query storage.FileSearchQuery) ([]storage.Analysis, uint64, error)
}

type searchResponse struct {
	Query    string         `json:"query"`
	Type     string         `json:"type,omitempty"`
	Page     int            `json:"page"`
	PageSize int            `json:"page_size"`
	Total    uint64         `json:"total"`
	Results  []searchResult `json:"results"`
}

type searchResult struct {
	ID        string    `json:"id"`
	Path      string    `json:"path"`
	Name      string    `json:"name"`
	Type      string    `json:"type"`
	MimeType  string    `json:"mime_type"`
	Size      uint64    `json:"size"`
	Title     string    `json:"title,omitempty"`
	Excerpt   string    `json:"excerpt"`
	Drive     string    `json:"drive"`
	ScannedAt time.Time `json:"scanned_at"`
}

// handleSearch answers GET /api/search.
//
//	q     text to look for; a file matches when it contains every word (required)
//	type  pdf, audio or image (optional)
//	page  page number, starting at 1 (default 1)
//	size  results per page (default 20, at most 100)
func (s *Server) handleSearch(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		w.Header().Set("Allow", http.MethodGet)
		writeError(w, http.StatusMethodNotAllowed, "use GET")
		return
	}

	params := r.URL.Query()
	text := strings.TrimSpace(params.Get("q"))
	if text == "" {
		writeError(w, http.StatusBadRequest, "parameter q is required")
		return
	}
	if utf8.RuneCountInString(text) > maxQueryLength {
		writeError(w, http.StatusBadRequest, "parameter q is too long (256 characters at most)")
		return
	}

	var kind mimetypes.Kind
	if raw := params.Get("type"); raw != "" {
		parsed, ok := mimetypes.ParseKind(raw)
		if !ok {
			writeError(w, http.StatusBadRequest, "parameter type must be pdf, audio or image")
			return
		}
		kind = parsed
	}

	page, ok := positiveInt(params.Get("page"), 1)
	if !ok {
		writeError(w, http.StatusBadRequest, "parameter page must be a positive number")
		return
	}
	size, ok := positiveInt(params.Get("size"), defaultPageSize)
	if !ok || size > maxPageSize {
		writeError(w, http.StatusBadRequest, "parameter size must be between 1 and 100")
		return
	}

	analyses, total, err := s.searcher.SearchFiles(r.Context(), storage.FileSearchQuery{
		Text:   text,
		Kind:   kind,
		Offset: (page - 1) * size,
		Limit:  size,
	})
	if err != nil {
		writeError(w, http.StatusInternalServerError, "search failed")
		return
	}

	terms := queryTerms(text)
	results := make([]searchResult, 0, len(analyses))
	for _, analysis := range analyses {
		results = append(results, toSearchResult(analysis, terms))
	}

	writeJSON(w, http.StatusOK, searchResponse{
		Query:    text,
		Type:     string(kind),
		Page:     page,
		PageSize: size,
		Total:    total,
		Results:  results,
	})
}

// positiveInt parses an optional query parameter that must be 1 or more.
func positiveInt(raw string, fallback int) (int, bool) {
	if raw == "" {
		return fallback, true
	}
	value, err := strconv.Atoi(raw)
	if err != nil || value < 1 {
		return 0, false
	}
	return value, true
}

func toSearchResult(analysis storage.Analysis, terms []string) searchResult {
	result := searchResult{
		ID:        analysis.EntityId.String(),
		Path:      analysis.Summary,
		Drive:     analysis.Namespace,
		ScannedAt: analysis.At,
	}

	var details storage.FileDetails
	switch payload := analysis.Payload.(type) {
	case storage.FileDetails:
		details = payload
	case *storage.FileDetails:
		if payload != nil {
			details = *payload
		}
	}
	if details.Filename != "" {
		result.Path = details.Filename
		// The summary starts as the path and becomes the document title once a
		// specialist has found one.
		if analysis.Summary != details.Filename {
			result.Title = analysis.Summary
		}
	}
	result.Name = baseName(result.Path)
	result.MimeType = details.MimeType
	result.Type = string(mimetypes.KindOf(details.MimeType))
	result.Size = details.Size
	result.Excerpt = excerpt(details.Content, terms)
	return result
}

// baseName returns the last element of a path, whether it was scanned on
// Windows or on Linux.
func baseName(path string) string {
	if i := strings.LastIndexAny(path, `/\`); i >= 0 {
		return path[i+1:]
	}
	return path
}

// queryTerms splits what the user typed into lowercase words.
func queryTerms(text string) []string {
	return strings.FieldsFunc(strings.ToLower(text), func(r rune) bool {
		return !unicode.IsLetter(r) && !unicode.IsDigit(r)
	})
}

// excerpt returns a short passage of the content around the first query word
// found in it, or the beginning of the content when the match was elsewhere
// (in the file name, for instance). Line breaks are flattened into spaces.
func excerpt(content string, terms []string) string {
	runes := []rune(strings.Join(strings.Fields(content), " "))
	if len(runes) == 0 {
		return ""
	}

	lowered := make([]rune, len(runes))
	for i, r := range runes {
		lowered[i] = unicode.ToLower(r)
	}

	start, matchLen := -1, 0
	for _, term := range terms {
		if i := indexRunes(lowered, []rune(term)); i >= 0 && (start < 0 || i < start) {
			start, matchLen = i, utf8.RuneCountInString(term)
		}
	}

	from, to := 0, min(len(runes), 2*excerptRadius)
	if start >= 0 {
		from = max(0, start-excerptRadius)
		to = min(len(runes), start+matchLen+excerptRadius)
	}

	passage := string(runes[from:to])
	if from > 0 {
		passage = "…" + passage
	}
	if to < len(runes) {
		passage += "…"
	}
	return passage
}

// indexRunes returns the position of needle in haystack, or -1.
func indexRunes(haystack, needle []rune) int {
	if len(needle) == 0 || len(needle) > len(haystack) {
		return -1
	}
	for i := 0; i+len(needle) <= len(haystack); i++ {
		match := true
		for j := range needle {
			if haystack[i+j] != needle[j] {
				match = false
				break
			}
		}
		if match {
			return i
		}
	}
	return -1
}
