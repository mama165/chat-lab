// Package catalog is the notebook the search runs on: one entry per file of
// the library, written once when the file is indexed, so that a question is
// answered without opening any file again.
//
// It executes the plans of domain/query. Exact criteria (kind, date, source,
// codes, page count, duration) filter; everything a model judged (what a
// picture shows, how many faces) only ranks.
package catalog

import (
	"chat-lab/domain/query"
	"fmt"
	"slices"
	"time"
)

// Item is what is known about one file.
type Item struct {
	// ID identifies the file for as long as it stays in the library: a media
	// store identifier on a phone, a path elsewhere.
	ID   string     `json:"id"`
	Path string     `json:"path"`
	Name string     `json:"name"`
	Kind query.Kind `json:"kind"`
	Mime string     `json:"mime,omitempty"`
	// Size is in bytes.
	Size int64 `json:"size,omitempty"`
	// AddedAt is when the file joined the library.
	AddedAt time.Time `json:"added_at"`
	// TakenAt is when a picture or a video was taken, when the file says so.
	TakenAt time.Time `json:"taken_at,omitzero"`
	// Source is where the file comes from, when known.
	Source query.Source `json:"source,omitempty"`
	// Country and City come from the location of a picture; empty when unknown.
	Country string `json:"country,omitempty"`
	City    string `json:"city,omitempty"`
	// Seconds is the duration of a video or a recording.
	Seconds int `json:"seconds,omitempty"`
	// Pages holds the text of each page of a document, in order. A scanned
	// page without recognized text is an empty string, so that page numbers
	// stay right.
	Pages []string `json:"pages,omitempty"`
	// PageCount is set from Pages when they are given.
	PageCount int `json:"page_count,omitempty"`
	// Text is what was read in a picture, or the transcript of a recording.
	Text string `json:"text,omitempty"`
	// Language is the language of the text, as an ISO 639-1 code.
	Language string `json:"language,omitempty"`
	// Codes are the contents of the QR codes and barcodes found in the file.
	Codes []string `json:"codes,omitempty"`
	// Faces is the number of faces found; nil when the file was not examined.
	Faces *int `json:"faces,omitempty"`
	// Portrait is set when faces are the subject of the picture.
	Portrait bool `json:"portrait,omitempty"`
	// Signature is set when a signature was found.
	Signature bool `json:"signature,omitempty"`
	// Vectors describe what the file shows: one for a picture, one per sampled
	// frame for a video. They come from the image side of the same model that
	// encodes the user's description.
	Vectors [][]float32 `json:"vectors,omitempty"`
	// Caption is a description in words. It only serves a stand-in encoder,
	// for tests and demonstrations without a vision model.
	Caption string `json:"caption,omitempty"`
}

// Date is the date a search compares with: when the file was taken if it says
// so, otherwise when it joined the library.
func (it Item) Date() time.Time {
	if !it.TakenAt.IsZero() {
		return it.TakenAt
	}
	return it.AddedAt
}

// HasCode reports whether a QR code or a barcode was found.
func (it Item) HasCode() bool {
	return len(it.Codes) > 0
}

func (it Item) validate() error {
	switch {
	case it.ID == "":
		return fmt.Errorf("catalog: item without an id (%q)", it.Path)
	case !slices.Contains(query.AllKinds, it.Kind):
		return fmt.Errorf("catalog: item %q has unknown kind %q", it.ID, it.Kind)
	case it.AddedAt.IsZero():
		return fmt.Errorf("catalog: item %q has no date", it.ID)
	}
	return nil
}
