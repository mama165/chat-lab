// Package query turns a free sentence ("find the PDF from the last 6 months
// about a dishwasher manual") into a search plan the engine can execute.
//
// The translation is deterministic: closed vocabularies (file kinds, relative
// dates, counts, positions) are handled by rules, and what remains of the
// sentence is kept as free meaning for the text encoder and the full-text
// index. Nothing here needs a model, a network or a phone.
package query

import (
	"slices"
	"time"
)

// Kind is a family of files the user can ask for.
type Kind string

const (
	KindImage Kind = "image"
	KindVideo Kind = "video"
	KindPDF   Kind = "pdf"
	KindAudio Kind = "audio"
)

// AllKinds lists every kind, in display order.
var AllKinds = []Kind{KindImage, KindVideo, KindPDF, KindAudio}

// Origin records which words of the sentence produced a criterion. A criterion
// without an origin was invented and is rejected by Validate: this is what
// keeps a language model from adding filters the user never asked for.
type Origin struct {
	// Start and End are byte offsets in the sentence (End excluded).
	Start int `json:"start"`
	End   int `json:"end"`
	// Text is the part of the sentence the criterion comes from.
	Text string `json:"text"`
	// Rule names what recognized it, for debugging and tests.
	Rule string `json:"rule,omitempty"`
}

// DateRange is a period of time. To is excluded.
type DateRange struct {
	From time.Time `json:"from"`
	To   time.Time `json:"to"`
	// Approx is set when the user gave a rough period ("a month ago"): the
	// range already includes a margin so that a relevant file is not missed.
	Approx bool `json:"approx,omitempty"`
	// Widened is set once Relax has enlarged the range.
	Widened bool   `json:"widened,omitempty"`
	Label   string `json:"label"`
	Origin  Origin `json:"origin"`
}

// Contains reports whether t falls in the range.
func (d DateRange) Contains(t time.Time) bool {
	return !t.Before(d.From) && t.Before(d.To)
}

// Visual is a description compared with the pictures themselves.
type Visual struct {
	// Description is given as is to the text encoder. It may be empty when the
	// user only said what is not wanted.
	Description string `json:"description"`
	// Not lists what the user does not want to see; it lowers a score, it never
	// excludes a file.
	Not    []string `json:"not,omitempty"`
	Origin Origin   `json:"origin"`
}

// TextScope says where the words must be looked for.
type TextScope string

const (
	// ScopeAny searches document text, recognized text and file names.
	ScopeAny TextScope = "any"
	// ScopeWritten means the user asked for words visible in the picture.
	ScopeWritten TextScope = "written"
)

// Term is one word or expression to look for, with its accepted variants.
type Term struct {
	Value        string   `json:"value"`
	Alternatives []string `json:"alternatives,omitempty"`
}

// TextQuery is what must be searched in the full-text index.
type TextQuery struct {
	Terms []Term `json:"terms"`
	// Not lists words the user excluded during the conversation.
	Not   []string  `json:"not,omitempty"`
	Scope TextScope `json:"scope"`
	// Weak is set when the words come from a visual description: they may help
	// through a file name or recognized text, but must never be required.
	Weak bool `json:"weak,omitempty"`
	// Phrase is the original wording, for an optional text encoder.
	Phrase string `json:"phrase"`
	Origin Origin `json:"origin"`
}

// Count is a number of people. It ranks results, it never filters them.
type Count struct {
	Value int `json:"value"`
	// Tolerance is how far a detected count may be and still rank first.
	Tolerance int `json:"tolerance,omitempty"`
	// AtLeast means "Value or more".
	AtLeast bool   `json:"at_least,omitempty"`
	Origin  Origin `json:"origin"`
}

// Level is a rough size.
type Level string

const (
	LevelSmall Level = "small"
	LevelLarge Level = "large"
)

// Hint is a rough size or duration; it ranks results.
type Hint struct {
	Level  Level  `json:"level"`
	Origin Origin `json:"origin"`
}

// Position is a place inside a document.
type Position string

const (
	PositionStart Position = "start"
	PositionEnd   Position = "end"
	// PositionPage is a given page; PositionHint.Page holds its number.
	PositionPage Position = "page"
)

// PositionHint says where in the document the element is expected.
type PositionHint struct {
	Where Position `json:"where"`
	// Page is the page number, starting at 1, when Where is PositionPage.
	Page   int    `json:"page,omitempty"`
	Origin Origin `json:"origin"`
}

// Source is where a file comes from on the phone.
type Source string

const (
	SourceDownload   Source = "download"
	SourceScreenshot Source = "screenshot"
	SourceMessaging  Source = "messaging"
)

// SourceFilter restricts the search to one origin of files.
type SourceFilter struct {
	Source Source `json:"source"`
	Origin Origin `json:"origin"`
}

// Place is where a picture or a video was taken: a country or a city. It
// ranks results and never excludes a file, because many files carry no
// location at all: those are kept, after the ones known to be at that place.
type Place struct {
	Name   string `json:"name"`
	Origin Origin `json:"origin"`
}

// Bound is a numeric limit; zero means that side is open.
type Bound struct {
	Min    int    `json:"min,omitempty"`
	Max    int    `json:"max,omitempty"`
	Origin Origin `json:"origin"`
}

// Language is the language of the text of a file, as an ISO 639-1 code.
type Language struct {
	Code   string `json:"code"`
	Origin Origin `json:"origin"`
}

// FeatureName is a fact computed once, when a file is indexed.
type FeatureName string

const (
	// FeatureCode is a QR code or a barcode found in the file.
	FeatureCode FeatureName = "code"
	// FeaturePortrait is a picture whose subject is one or several faces.
	FeaturePortrait FeatureName = "portrait"
	// FeatureSignature is a signature, handwritten or electronic.
	FeatureSignature FeatureName = "signature"
	// FeatureText is readable text in a picture, whatever it says.
	FeatureText FeatureName = "text"
)

// Feature is a required or preferred fact about a file.
type Feature struct {
	Name   FeatureName `json:"name"`
	Origin Origin      `json:"origin"`
}

// Sort is the order of the results.
type Sort string

const (
	SortRelevance Sort = "relevance"
	SortNewest    Sort = "newest"
	SortOldest    Sort = "oldest"
	SortLargest   Sort = "largest"
	SortSmallest  Sort = "smallest"
)

// Cardinality is how many results the user expects.
type Cardinality string

const (
	// CardinalityOne: the user is after one precise file.
	CardinalityOne Cardinality = "one"
	// CardinalityMany: a list, best matches first.
	CardinalityMany Cardinality = "many"
	// CardinalityAll: every match, nothing cut.
	CardinalityAll Cardinality = "all"
)

// Plan is a search request, ready to be executed.
//
// Only Kinds, Date, Source and the code feature are filters, because they rely
// on exact metadata. Every other criterion ranks the results: a relevant file
// that a model judged badly must still appear, lower in the list.
type Plan struct {
	Kinds        []Kind        `json:"kinds,omitempty"`
	KindsOrigin  *Origin       `json:"kinds_origin,omitempty"`
	Date         *DateRange    `json:"date,omitempty"`
	ExcludeDates []DateRange   `json:"exclude_dates,omitempty"`
	Visual       *Visual       `json:"visual,omitempty"`
	Text         *TextQuery    `json:"text,omitempty"`
	People       *Count        `json:"people,omitempty"`
	Features     []Feature     `json:"features,omitempty"`
	Size         *Hint         `json:"size,omitempty"`
	Duration     *Hint         `json:"duration,omitempty"`
	Position     *PositionHint `json:"position,omitempty"`
	Source       *SourceFilter `json:"source,omitempty"`
	Place        *Place        `json:"place,omitempty"`
	// Pages bounds the number of pages of a document.
	Pages *Bound `json:"pages,omitempty"`
	// Seconds bounds the duration of a video or a recording.
	Seconds     *Bound      `json:"seconds,omitempty"`
	Language    *Language   `json:"language,omitempty"`
	Sort        Sort        `json:"sort"`
	Cardinality Cardinality `json:"cardinality"`
	// Limit is the number of results asked for ("les trois dernières vidéos");
	// zero means no limit was given.
	Limit int `json:"limit,omitempty"`
}

// HasKind reports whether the plan is limited to (or includes) a kind.
func (p Plan) HasKind(k Kind) bool {
	return slices.Contains(p.Kinds, k)
}

// HasFeature reports whether a feature is requested.
func (p Plan) HasFeature(name FeatureName) bool {
	return slices.ContainsFunc(p.Features, func(f Feature) bool { return f.Name == name })
}

// IsEmpty reports whether the plan holds no criterion at all.
func (p Plan) IsEmpty() bool {
	return len(p.Kinds) == 0 && p.Date == nil && len(p.ExcludeDates) == 0 &&
		p.Visual == nil && p.Text == nil && p.People == nil && len(p.Features) == 0 &&
		p.Size == nil && p.Duration == nil && p.Position == nil && p.Source == nil &&
		p.Place == nil && p.Pages == nil && p.Seconds == nil && p.Language == nil
}

// Clone returns a deep copy, so that refining a plan never alters the previous one.
func (p Plan) Clone() Plan {
	c := p
	c.Kinds = slices.Clone(p.Kinds)
	c.ExcludeDates = slices.Clone(p.ExcludeDates)
	c.Features = slices.Clone(p.Features)
	c.KindsOrigin = clonePtr(p.KindsOrigin)
	c.Date = clonePtr(p.Date)
	c.People = clonePtr(p.People)
	c.Size = clonePtr(p.Size)
	c.Duration = clonePtr(p.Duration)
	c.Position = clonePtr(p.Position)
	c.Source = clonePtr(p.Source)
	c.Place = clonePtr(p.Place)
	c.Pages = clonePtr(p.Pages)
	c.Seconds = clonePtr(p.Seconds)
	c.Language = clonePtr(p.Language)
	if p.Visual != nil {
		v := *p.Visual
		v.Not = slices.Clone(p.Visual.Not)
		c.Visual = &v
	}
	if p.Text != nil {
		t := *p.Text
		t.Not = slices.Clone(p.Text.Not)
		t.Terms = make([]Term, len(p.Text.Terms))
		for i, term := range p.Text.Terms {
			t.Terms[i] = Term{Value: term.Value, Alternatives: slices.Clone(term.Alternatives)}
		}
		c.Text = &t
	}
	return c
}

func clonePtr[T any](v *T) *T {
	if v == nil {
		return nil
	}
	c := *v
	return &c
}

// criterion is one filled field of a plan, with the words it comes from.
type criterion struct {
	field  string
	origin Origin
}

// criteria lists every filled field, for validation.
func (p Plan) criteria() []criterion {
	var out []criterion
	if len(p.Kinds) > 0 && p.KindsOrigin != nil {
		out = append(out, criterion{"kinds", *p.KindsOrigin})
	} else if len(p.Kinds) > 0 {
		out = append(out, criterion{"kinds", Origin{}})
	}
	if p.Date != nil {
		out = append(out, criterion{"date", p.Date.Origin})
	}
	for _, d := range p.ExcludeDates {
		out = append(out, criterion{"exclude_dates", d.Origin})
	}
	if p.Visual != nil {
		out = append(out, criterion{"visual", p.Visual.Origin})
	}
	if p.Text != nil {
		out = append(out, criterion{"text", p.Text.Origin})
	}
	if p.People != nil {
		out = append(out, criterion{"people", p.People.Origin})
	}
	for _, f := range p.Features {
		out = append(out, criterion{"features", f.Origin})
	}
	if p.Size != nil {
		out = append(out, criterion{"size", p.Size.Origin})
	}
	if p.Duration != nil {
		out = append(out, criterion{"duration", p.Duration.Origin})
	}
	if p.Position != nil {
		out = append(out, criterion{"position", p.Position.Origin})
	}
	if p.Source != nil {
		out = append(out, criterion{"source", p.Source.Origin})
	}
	if p.Place != nil {
		out = append(out, criterion{"place", p.Place.Origin})
	}
	if p.Pages != nil {
		out = append(out, criterion{"pages", p.Pages.Origin})
	}
	if p.Seconds != nil {
		out = append(out, criterion{"seconds", p.Seconds.Origin})
	}
	if p.Language != nil {
		out = append(out, criterion{"language", p.Language.Origin})
	}
	return out
}
