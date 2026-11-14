package catalog

import (
	"bytes"
	"chat-lab/domain/query"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"image"
	_ "image/gif" // registers the GIF decoder
	_ "image/jpeg"
	_ "image/png"
	"io/fs"
	"os"
	"os/exec"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
)

// PDFReader gives the text of each page of a document, in order. An empty
// string is a page without recognized text; it keeps the page numbers right.
type PDFReader interface {
	Pages(ctx context.Context, path string) ([]string, error)
}

// PopplerReader reads PDF pages with pdftotext, from the poppler tools.
type PopplerReader struct{}

// Pages implements PDFReader.
func (PopplerReader) Pages(ctx context.Context, path string) ([]string, error) {
	info, err := exec.CommandContext(ctx, "pdfinfo", path).Output()
	if err != nil {
		return nil, fmt.Errorf("pdfinfo: %w", err)
	}
	count := 0
	for _, line := range strings.Split(string(info), "\n") {
		if value, ok := strings.CutPrefix(line, "Pages:"); ok {
			count, _ = strconv.Atoi(strings.TrimSpace(value))
		}
	}
	pages := make([]string, 0, count)
	for page := 1; page <= count; page++ {
		text, err := exec.CommandContext(ctx, "pdftotext", "-layout", "-f", strconv.Itoa(page), "-l", strconv.Itoa(page), path, "-").Output()
		if err != nil {
			return nil, fmt.Errorf("pdftotext page %d: %w", page, err)
		}
		pages = append(pages, strings.TrimSpace(string(text)))
	}
	return pages, nil
}

// IngestOptions configure a pass over a folder.
type IngestOptions struct {
	// PDF reads the pages of documents. Without it, documents are indexed by
	// name only.
	PDF PDFReader
	// Captions give what a picture shows, by relative path. They stand in for
	// the vision model until it runs: see WordEncoder.
	Captions map[string]string
	// Encoder turns captions into vectors.
	Encoder WordEncoder
}

// Report says what a pass did.
type Report struct {
	Added     int      `json:"added"`
	Updated   int      `json:"updated"`
	Unchanged int      `json:"unchanged"`
	Removed   int      `json:"removed"`
	Skipped   []string `json:"skipped,omitempty"`
}

// kindOf tells the kind of a file from its extension. A file of another kind
// is skipped, not guessed.
func kindOf(name string) (query.Kind, bool) {
	switch strings.ToLower(filepath.Ext(name)) {
	case ".pdf":
		return query.KindPDF, true
	case ".jpg", ".jpeg", ".png", ".gif":
		return query.KindImage, true
	case ".mp4", ".mov", ".webm":
		return query.KindVideo, true
	case ".m4a", ".mp3", ".wav", ".aiff", ".ogg":
		return query.KindAudio, true
	}
	return "", false
}

// sourceOf tells where a file came from from the folder it is in: a phone
// puts downloads, chats and screenshots in folders of their own.
func sourceOf(rel string) query.Source {
	folders := strings.ToLower(filepath.ToSlash(filepath.Dir(rel)))
	name := strings.ToLower(filepath.Base(rel))
	switch {
	case strings.Contains(folders, "whatsapp"), strings.Contains(folders, "telegram"), strings.Contains(folders, "messenger"):
		return query.SourceMessaging
	case strings.Contains(folders, "screenshot"), strings.Contains(folders, "capture"),
		strings.Contains(name, "screenshot"), strings.HasPrefix(name, "capture"):
		return query.SourceScreenshot
	case strings.HasPrefix(folders, "download"), strings.Contains(folders, "/download"):
		return query.SourceDownload
	}
	return ""
}

// Ingest brings the catalog in line with a folder: new and changed files are
// read, unchanged ones are skipped, and entries of files that disappeared are
// removed. A file is unchanged when its size and modification time are the
// same as when it was read.
func Ingest(ctx context.Context, c *Catalog, root string, opts IngestOptions) (Report, error) {
	var report Report
	seen := make(map[string]struct{})
	var batch []Item

	err := filepath.WalkDir(root, func(path string, entry fs.DirEntry, err error) error {
		if err != nil {
			report.Skipped = append(report.Skipped, path+": "+err.Error())
			return nil
		}
		if err := ctx.Err(); err != nil {
			return err
		}
		if entry.IsDir() || entry.Type()&fs.ModeSymlink != 0 {
			return nil
		}
		rel, err := filepath.Rel(root, path)
		if err != nil {
			return err
		}
		rel = filepath.ToSlash(rel)
		kind, ok := kindOf(entry.Name())
		if !ok {
			return nil
		}
		info, err := entry.Info()
		if err != nil {
			report.Skipped = append(report.Skipped, rel+": "+err.Error())
			return nil
		}
		seen[rel] = struct{}{}

		previous, known := c.Get(rel)
		modified := info.ModTime().UTC()
		if known && previous.Size == info.Size() && previous.AddedAt.Equal(modified) {
			report.Unchanged++
			return nil
		}

		item := Item{
			ID: rel, Path: path, Name: entry.Name(), Kind: kind,
			Size: info.Size(), AddedAt: modified, Source: sourceOf(rel),
		}
		if err := readFile(ctx, &item, opts); err != nil {
			report.Skipped = append(report.Skipped, rel+": "+err.Error())
			return nil
		}
		batch = append(batch, item)
		if known {
			report.Updated++
		} else {
			report.Added++
		}
		return nil
	})
	if err != nil {
		return report, err
	}

	var gone []string
	for _, item := range c.All() {
		if _, present := seen[item.ID]; !present {
			gone = append(gone, item.ID)
		}
	}
	slices.Sort(gone)
	if err := c.Delete(gone...); err != nil {
		return report, err
	}
	report.Removed = len(gone)

	if err := c.Put(batch...); err != nil {
		return report, err
	}
	return report, c.Flush()
}

// readFile fills in what a file says about itself. A file that cannot be read
// is an error: it is reported, not indexed by half.
func readFile(ctx context.Context, item *Item, opts IngestOptions) error {
	switch item.Kind {
	case query.KindImage:
		file, err := os.Open(item.Path)
		if err != nil {
			return err
		}
		defer file.Close()
		if _, _, err := image.DecodeConfig(file); err != nil {
			return fmt.Errorf("not a readable picture: %w", err)
		}
		item.Caption = opts.Captions[item.ID]
		if item.Caption != "" {
			opts.Encoder.Describe(item)
		}
	case query.KindPDF:
		if opts.PDF == nil {
			return nil
		}
		pages, err := opts.PDF.Pages(ctx, item.Path)
		if err != nil {
			return fmt.Errorf("not a readable document: %w", err)
		}
		item.Pages = pages
		item.PageCount = len(pages)
		if len(pages) == 0 {
			return errors.New("no page")
		}
	case query.KindVideo, query.KindAudio:
		item.Caption = opts.Captions[item.ID]
		if item.Caption != "" {
			opts.Encoder.Describe(item)
		}
	}
	return nil
}

// Captions reads a JSON object mapping relative paths to what the file shows.
func Captions(data []byte) (map[string]string, error) {
	var out map[string]string
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&out); err != nil {
		return nil, fmt.Errorf("reading captions: %w", err)
	}
	return out, nil
}
