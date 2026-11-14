package query

import (
	"bytes"
	"encoding/json"
	"fmt"
	"slices"
	"strings"
	"time"
)

// A plan is checked before any result is shown. The parser of this package
// always passes; the check exists for plans coming from elsewhere, above all
// from a language model, which can produce a well-formed plan holding a filter
// nobody asked for.

// IssueCode names what is wrong with a plan.
type IssueCode string

const (
	IssueEmpty        IssueCode = "empty_plan"
	IssueUnknownValue IssueCode = "unknown_value"
	IssueDateInverted IssueCode = "date_inverted"
	IssueDateFuture   IssueCode = "date_future"
	IssueDateTooOld   IssueCode = "date_too_old"
	IssueCountRange   IssueCode = "count_out_of_range"
	IssueTooLong      IssueCode = "too_long"
	// IssueUntraceable: a criterion that no word of the user accounts for.
	IssueUntraceable IssueCode = "untraceable"
)

// Issue is one problem found in a plan.
type Issue struct {
	Field   string    `json:"field"`
	Code    IssueCode `json:"code"`
	Message string    `json:"message"`
}

func (i Issue) String() string {
	return fmt.Sprintf("%s: %s (%s)", i.Field, i.Message, i.Code)
}

const (
	maxDescriptionLength = 256
	maxPeople            = 100
	maxLimit             = 1000
)

// Validate checks a plan against what the user said. sentences holds every
// sentence of the conversation that built the plan. It returns no issue for a
// plan that can be executed as is.
func Validate(plan Plan, now time.Time, sentences ...string) []Issue {
	var issues []Issue
	add := func(field string, code IssueCode, format string, args ...any) {
		issues = append(issues, Issue{Field: field, Code: code, Message: fmt.Sprintf(format, args...)})
	}

	if plan.IsEmpty() {
		add("plan", IssueEmpty, "the plan holds no criterion")
	}
	for _, k := range plan.Kinds {
		if !slices.Contains(AllKinds, k) {
			add("kinds", IssueUnknownValue, "unknown kind %q", k)
		}
	}
	checkDate := func(field string, d DateRange) {
		switch {
		case !d.From.Before(d.To):
			add(field, IssueDateInverted, "the period ends before it starts")
		case d.From.After(now):
			add(field, IssueDateFuture, "the period starts in the future")
		case d.To.Before(earliest):
			add(field, IssueDateTooOld, "the period ends before %d", earliest.Year())
		}
	}
	if plan.Date != nil {
		checkDate("date", *plan.Date)
	}
	for _, d := range plan.ExcludeDates {
		checkDate("exclude_dates", d)
	}
	if plan.Visual != nil {
		switch {
		case strings.TrimSpace(plan.Visual.Description) == "":
			add("visual", IssueEmpty, "the description is empty")
		case len([]rune(plan.Visual.Description)) > maxDescriptionLength:
			add("visual", IssueTooLong, "the description is longer than %d characters", maxDescriptionLength)
		}
	}
	if plan.Text != nil {
		if len(plan.Text.Terms) == 0 {
			add("text", IssueEmpty, "no word to search")
		}
		if plan.Text.Scope != ScopeAny && plan.Text.Scope != ScopeWritten {
			add("text", IssueUnknownValue, "unknown scope %q", plan.Text.Scope)
		}
	}
	if plan.People != nil && (plan.People.Value < 0 || plan.People.Value > maxPeople) {
		add("people", IssueCountRange, "a count of %d people is out of range", plan.People.Value)
	}
	for _, f := range plan.Features {
		if !slices.Contains([]FeatureName{FeatureCode, FeaturePortrait, FeatureSignature}, f.Name) {
			add("features", IssueUnknownValue, "unknown feature %q", f.Name)
		}
	}
	for field, hint := range map[string]*Hint{"size": plan.Size, "duration": plan.Duration} {
		if hint != nil && hint.Level != LevelSmall && hint.Level != LevelLarge {
			add(field, IssueUnknownValue, "unknown level %q", hint.Level)
		}
	}
	if plan.Position != nil {
		switch plan.Position.Where {
		case PositionStart, PositionEnd:
		case PositionPage:
			if plan.Position.Page < 1 {
				add("position", IssueCountRange, "page %d does not exist", plan.Position.Page)
			}
		default:
			add("position", IssueUnknownValue, "unknown position %q", plan.Position.Where)
		}
	}
	if plan.Limit < 0 || plan.Limit > maxLimit {
		add("limit", IssueCountRange, "a limit of %d results is out of range", plan.Limit)
	}
	if plan.Source != nil && !slices.Contains([]Source{SourceDownload, SourceScreenshot, SourceMessaging}, plan.Source.Source) {
		add("source", IssueUnknownValue, "unknown source %q", plan.Source.Source)
	}
	if !slices.Contains([]Sort{SortRelevance, SortNewest, SortOldest, SortLargest, SortSmallest}, plan.Sort) {
		add("sort", IssueUnknownValue, "unknown sort %q", plan.Sort)
	}
	if !slices.Contains([]Cardinality{CardinalityOne, CardinalityMany, CardinalityAll}, plan.Cardinality) {
		add("cardinality", IssueUnknownValue, "unknown cardinality %q", plan.Cardinality)
	}

	said := make(map[string]struct{})
	for _, sentence := range sentences {
		for _, t := range tokenize(sentence) {
			said[t.norm] = struct{}{}
		}
	}
	for _, c := range plan.criteria() {
		words := tokenize(c.origin.Text)
		if len(words) == 0 {
			add(c.field, IssueUntraceable, "no word of the user accounts for this criterion")
			continue
		}
		for _, w := range words {
			if _, ok := said[w.norm]; !ok {
				add(c.field, IssueUntraceable, "%q was not said by the user", c.origin.Text)
				break
			}
		}
	}
	return issues
}

// ParseJSON reads a plan written by another component, typically a language
// model constrained to this format, and checks it. A plan with any issue is
// refused: the caller falls back on the plan of the rules.
func ParseJSON(data []byte, now time.Time, sentences ...string) (Plan, []Issue, error) {
	var plan Plan
	decoder := json.NewDecoder(bytes.NewReader(data))
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(&plan); err != nil {
		return Plan{}, nil, fmt.Errorf("reading plan: %w", err)
	}
	if plan.Sort == "" {
		plan.Sort = SortRelevance
	}
	if plan.Cardinality == "" {
		plan.Cardinality = CardinalityMany
	}
	if issues := Validate(plan, now, sentences...); len(issues) > 0 {
		return Plan{}, issues, fmt.Errorf("plan refused: %d issue(s), first: %s", len(issues), issues[0])
	}
	return plan, nil, nil
}

// RelaxStep names a filter that was loosened.
type RelaxStep string

const (
	RelaxSource       RelaxStep = "source"
	RelaxDateWidened  RelaxStep = "date_widened"
	RelaxDateDropped  RelaxStep = "date_dropped"
	RelaxExcludedDate RelaxStep = "excluded_dates"
	RelaxWritten      RelaxStep = "written"
)

// Relaxation tells the user which filter was loosened, and why results appear
// that do not match everything that was asked.
type Relaxation struct {
	Step    RelaxStep `json:"step"`
	Message string    `json:"message"`
}

// Relax loosens one filter of a plan that returned nothing, starting with the
// least reliable one. It is called again while there is still no result, and
// reports false once nothing can be loosened. The kind of file and the
// presence of a code are never dropped: showing a photo to someone who asked
// for a PDF is not an answer.
func Relax(plan Plan, now time.Time) (Plan, Relaxation, bool) {
	next := plan.Clone()
	switch {
	case next.Source != nil:
		next.Source = nil
		return next, Relaxation{RelaxSource, "Rien à cet emplacement : recherche étendue à toute la bibliothèque."}, true

	case next.Date != nil && !next.Date.Widened:
		d := next.Date
		length := d.To.Sub(d.From)
		d.From = d.From.Add(-length)
		if floor := earliest.In(d.From.Location()); d.From.Before(floor) {
			d.From = floor
		}
		d.To = d.To.Add(length)
		if end := tomorrow(now); d.To.After(end) {
			d.To = end
		}
		d.Widened, d.Approx = true, true
		d.Label = formatRange(d.From, d.To, now)
		return next, Relaxation{RelaxDateWidened, "Rien sur cette période : période élargie (" + d.Label + ")."}, true

	case next.Date != nil:
		next.Date = nil
		return next, Relaxation{RelaxDateDropped, "Rien sur cette période : recherche sans limite de date."}, true

	case len(next.ExcludeDates) > 0:
		next.ExcludeDates = nil
		return next, Relaxation{RelaxExcludedDate, "Rien d'autre : les périodes écartées sont de nouveau incluses."}, true

	case next.Text != nil && next.Text.Scope == ScopeWritten && next.Visual == nil && next.Text.Phrase != "":
		next.Visual = &Visual{Description: next.Text.Phrase, Origin: next.Text.Origin}
		return next, Relaxation{RelaxWritten, "Ce texte n'a été lu dans aucune image : recherche étendue à ce que les images montrent."}, true
	}
	return plan, Relaxation{}, false
}
