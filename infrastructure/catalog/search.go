package catalog

import (
	"chat-lab/domain/query"
	"cmp"
	"context"
	"fmt"
	"math/bits"
	"slices"
	"strconv"
	"strings"
	"time"
	"unicode"

	"github.com/blugelabs/bluge"
)

// TextEncoder turns a description into a vector comparable with the vectors
// of the pictures. On a phone it is the text side of the vision model.
type TextEncoder interface {
	EncodeText(ctx context.Context, text string) ([]float32, error)
}

// Options tune one search.
type Options struct {
	// Encoder compares descriptions with pictures. Without it, a description
	// is only searched as words, in file names and recognized text.
	Encoder TextEncoder
	// Offset and Size select the page of results; Size defaults to 20.
	Offset, Size int
	// Now is the current time, used when a period has to be widened.
	Now time.Time
	// VisualMargin is how far below the best picture another one still counts
	// as showing what was described. It depends on the vision model and has to
	// be calibrated with it; the default is a placeholder.
	VisualMargin float32
	// VisualFloor is the resemblance below which a picture does not show what
	// was described, however it compares with the others: when nothing in the
	// library looks like the description, nothing is a match. Model-dependent
	// as well.
	VisualFloor float32
}

const (
	defaultSize         = 20
	defaultVisualMargin = 0.10
	defaultVisualFloor  = 0.15
	maxRelaxations      = 8
	// rrfK is the usual constant of reciprocal rank fusion: it keeps the top
	// of a list from crushing everything else.
	rrfK = 60
)

// Hit is one result.
type Hit struct {
	Item Item `json:"item"`
	// Page is the page of the document where the words were found, starting
	// at 1; zero when the match is not on a page.
	Page int `json:"page,omitempty"`
	// Excerpt is the text around the words found.
	Excerpt string `json:"excerpt,omitempty"`
	// Beyond is set on results past the real matches: pictures ranked by
	// resemblance that are shown so that nothing is silently cut, but that are
	// not counted in Total.
	Beyond bool `json:"beyond,omitempty"`
	// Notes say what is uncertain about the result: "lieu inconnu".
	Notes []string `json:"notes,omitempty"`
	Score float64  `json:"score"`
}

// Results is the answer to a plan.
type Results struct {
	// Plan is the plan that was run: the one asked, or a loosened one.
	Plan query.Plan `json:"plan"`
	// Total is the number of matches, whatever the page asked.
	Total int   `json:"total"`
	Hits  []Hit `json:"hits"`
	// Relaxed lists the filters that had to be loosened to find something.
	Relaxed []query.Relaxation `json:"relaxed,omitempty"`
	// VisualSkipped is set when the plan describes pictures but no encoder was
	// given: the description was only searched as words.
	VisualSkipped bool `json:"visual_skipped,omitempty"`
}

// Search runs a plan. When nothing matches, the plan is loosened one filter at
// a time, and the answer says which.
func (c *Catalog) Search(ctx context.Context, plan query.Plan, opts Options) (Results, error) {
	if opts.Size <= 0 {
		opts.Size = defaultSize
	}
	if opts.Offset < 0 {
		opts.Offset = 0
	}
	if opts.VisualMargin <= 0 {
		opts.VisualMargin = defaultVisualMargin
	}
	if opts.VisualFloor <= 0 {
		opts.VisualFloor = defaultVisualFloor
	}
	if opts.Now.IsZero() {
		opts.Now = time.Now()
	}

	var relaxed []query.Relaxation
	for {
		results, err := c.searchOnce(ctx, plan, opts)
		if err != nil {
			return Results{}, err
		}
		results.Relaxed = relaxed
		if results.Total > 0 || len(relaxed) >= maxRelaxations {
			return results, nil
		}
		next, relaxation, ok := query.Relax(plan, opts.Now)
		if !ok {
			return results, nil
		}
		plan = next
		relaxed = append(relaxed, relaxation)
	}
}

// candidate is a file being ranked.
type candidate struct {
	item *Item
	// tier 0 is a sure match; tier 1 holds files that cannot be ruled out
	// (unknown place, unknown language) and are listed after.
	tier   int
	notes  []string
	score  float64
	page   int
	beyond bool
}

func (c *Catalog) searchOnce(ctx context.Context, plan query.Plan, opts Options) (Results, error) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	results := Results{Plan: plan, Hits: []Hit{}}

	eligible := make(map[string]*candidate)
	for id, item := range c.items {
		if tier, notes, ok := admit(item, plan); ok {
			eligible[id] = &candidate{item: item, tier: tier, notes: notes}
		}
	}

	visual, shows, err := c.visualRanking(ctx, plan, opts, eligible)
	if err != nil {
		return Results{}, err
	}
	described := plan.Visual != nil && plan.Visual.Description != ""
	results.VisualSkipped = described && opts.Encoder == nil

	var (
		text     []scored
		coverage map[string]float64
	)
	// Words coming from a visual description are only a hint, unless there is
	// nothing else to go by.
	weak := plan.Text != nil && plan.Text.Weak && visual != nil
	if plan.Text != nil {
		if text, coverage, err = c.textRanking(ctx, plan, eligible); err != nil {
			return Results{}, err
		}
	}

	var ranked []*candidate
	if plan.Text == nil && !described {
		// Only filters: every file that passes them is a match.
		for _, cand := range eligible {
			cand.score = 1
			ranked = append(ranked, cand)
		}
	} else {
		// Each ranking gives a file a score from its position only, so that
		// scores of different natures can be combined.
		position := func(list []scored) map[string]float64 {
			scores := make(map[string]float64, len(list))
			for rank, s := range list {
				scores[s.id] = 1 / float64(rrfK+rank+1)
			}
			return scores
		}
		byText, byLook := position(text), position(visual)
		pages := make(map[string]int, len(text))
		for _, s := range text {
			pages[s.id] = s.page
		}
		for id, cand := range eligible {
			words, hasWords := byText[id]
			look, hasLook := byLook[id]
			_, showsIt := shows[id]
			if weak {
				words *= 0.5
			}
			switch {
			case hasWords && !weak, showsIt:
				// A match: the words asked for are in the file, or it shows
				// what was described.
			case hasLook:
				cand.beyond = true
			default:
				continue
			}
			// The better of the two counts, the other adds a little: a
			// document, which has no picture to compare, must not lose to a
			// photo merely because the photo is in both rankings.
			cand.score = max(words, look) + 0.25*min(words, look)
			if plan.Text != nil && !weak {
				// A file holding every word asked for comes before one holding some.
				cand.score *= 0.4 + 0.6*coverage[id]
			}
			cand.page = pages[id]
			ranked = append(ranked, cand)
		}
	}

	applyHints(ranked, plan)
	order(ranked, plan)

	// Pictures past the real matches are only shown when results are ordered
	// by resemblance: sorted by date, they would be noise.
	if plan.Sort != query.SortRelevance || plan.Limit > 0 {
		ranked = slices.DeleteFunc(ranked, func(cand *candidate) bool { return cand.beyond })
	}
	for _, cand := range ranked {
		if !cand.beyond {
			results.Total++
		}
	}
	if plan.Limit > 0 && len(ranked) > plan.Limit {
		ranked = ranked[:plan.Limit]
		results.Total = plan.Limit
	}

	if opts.Offset >= len(ranked) {
		return results, nil
	}
	page := ranked[opts.Offset:min(opts.Offset+opts.Size, len(ranked))]
	reader, err := c.writer.Reader()
	if err != nil {
		return Results{}, fmt.Errorf("catalog: reading index: %w", err)
	}
	defer reader.Close()
	for _, cand := range page {
		hit := Hit{Item: *cand.item, Page: cand.page, Beyond: cand.beyond, Notes: cand.notes, Score: cand.score}
		if plan.Text != nil {
			docID := fileDocID(cand.item.ID)
			page := cand.page
			// A document found by its name has no page with the words: its
			// first page stands for it.
			if page == 0 && cand.item.Kind == query.KindPDF && cand.item.PageCount > 0 {
				page = 1
			}
			if page > 0 {
				docID = pageDocID(cand.item.ID, page)
			}
			hit.Page = page
			hit.Excerpt = excerpt(storedBody(ctx, reader, docID), plan.Text)
		}
		results.Hits = append(results.Hits, hit)
	}
	return results, nil
}

// admit applies the exact criteria of a plan to a file. It reports whether
// the file can be a result, and in which tier.
func admit(item *Item, plan query.Plan) (tier int, notes []string, ok bool) {
	if len(plan.Kinds) > 0 && !slices.Contains(plan.Kinds, item.Kind) {
		return 0, nil, false
	}
	date := item.Date()
	if plan.Date != nil && !plan.Date.Contains(date) {
		return 0, nil, false
	}
	for _, excluded := range plan.ExcludeDates {
		if excluded.Contains(date) {
			return 0, nil, false
		}
	}
	if plan.Source != nil && item.Source != plan.Source.Source {
		return 0, nil, false
	}
	if plan.HasFeature(query.FeatureCode) && !item.HasCode() {
		return 0, nil, false
	}
	if plan.HasFeature(query.FeatureText) && strings.TrimSpace(item.Text) == "" && item.PageCount == 0 {
		return 0, nil, false
	}
	within := func(bound *query.Bound, value int) bool {
		if bound == nil {
			return true
		}
		// A file whose size is not known cannot be said to fit a limit.
		return value > 0 && value >= bound.Min && (bound.Max == 0 || value <= bound.Max)
	}
	if !within(plan.Pages, item.PageCount) || !within(plan.Seconds, item.Seconds) {
		return 0, nil, false
	}

	if plan.Place != nil {
		wanted := query.Fold(plan.Place.Name)
		located := item.Country != "" || item.City != ""
		switch {
		case query.Fold(item.Country) == wanted || query.Fold(item.City) == wanted:
		case located:
			// Taken somewhere else, and known to be.
			return 0, nil, false
		case strings.Contains(" "+query.Fold(item.Name+" "+item.Path)+" ", " "+wanted+" "):
			notes = append(notes, "lieu d'après le nom du fichier")
		default:
			// No location in the file: it is kept, after the located ones.
			tier = 1
			notes = append(notes, "lieu inconnu")
		}
	}
	if plan.Language != nil {
		switch item.Language {
		case plan.Language.Code:
		case "":
			tier = 1
			notes = append(notes, "langue inconnue")
		default:
			return 0, nil, false
		}
	}
	return tier, notes, true
}

// scored is a file in one ranking, best first.
type scored struct {
	id    string
	score float64
	page  int
}

// visualRanking orders the eligible files by resemblance with the description
// of the plan, and returns the set of those that show it. Files showing what
// the user excluded are removed from the eligible ones.
func (c *Catalog) visualRanking(ctx context.Context, plan query.Plan, opts Options, eligible map[string]*candidate) ([]scored, map[string]struct{}, error) {
	if plan.Visual == nil || opts.Encoder == nil {
		return nil, nil, nil
	}
	encode := func(text string) ([]float32, error) {
		vector, err := opts.Encoder.EncodeText(ctx, text)
		if err != nil {
			return nil, fmt.Errorf("catalog: encoding %q: %w", text, err)
		}
		return normalized(vector), nil
	}
	// rank orders the eligible files by resemblance with one description, and
	// tells which ones are within the margin of the best.
	rank := func(text string) ([]scored, map[string]float64, map[string]struct{}, error) {
		vector, err := encode(text)
		if err != nil || vector == nil {
			return nil, nil, nil, err
		}
		var list []scored
		sims := make(map[string]float64)
		for id := range eligible {
			if sim, ok := best(vector, c.vectors[id]); ok {
				list = append(list, scored{id: id, score: float64(sim)})
				sims[id] = float64(sim)
			}
		}
		slices.SortFunc(list, func(a, b scored) int {
			return cmp.Or(cmp.Compare(b.score, a.score), cmp.Compare(a.id, b.id))
		})
		shows := make(map[string]struct{})
		for _, s := range list {
			if s.score >= list[0].score-float64(opts.VisualMargin) && s.score >= float64(opts.VisualFloor) {
				shows[s.id] = struct{}{}
			}
		}
		return list, sims, shows, nil
	}

	var (
		list  []scored
		sims  map[string]float64
		shows map[string]struct{}
		err   error
	)
	if plan.Visual.Description != "" {
		if list, sims, shows, err = rank(plan.Visual.Description); err != nil {
			return nil, nil, err
		}
	}
	for _, unwanted := range plan.Visual.Not {
		_, notSims, showsNot, err := rank(unwanted)
		if err != nil {
			return nil, nil, err
		}
		for id := range showsNot {
			// Excluded when it can be told: the file shows what is not
			// wanted, and shows it more than what is.
			if sim, described := sims[id]; !described || notSims[id] > sim {
				delete(eligible, id)
				delete(shows, id)
			}
		}
	}
	return list, shows, nil
}

// textRanking finds the eligible files holding words of the plan. For each it
// returns the page where they are best found, and the share of the words
// asked for that the file holds.
func (c *Catalog) textRanking(ctx context.Context, plan query.Plan, eligible map[string]*candidate) ([]scored, map[string]float64, error) {
	reader, err := c.writer.Reader()
	if err != nil {
		return nil, nil, fmt.Errorf("catalog: reading index: %w", err)
	}
	defer reader.Close()

	type place struct {
		id   string
		page int
	}
	type found struct {
		score float64
		terms uint64
	}
	terms := plan.Text.Terms
	if len(terms) > 64 {
		terms = terms[:64]
	}
	onPage := make(map[place]*found)
	inFile := make(map[string]uint64)
	for index, term := range terms {
		matches, err := reader.Search(ctx, bluge.NewAllMatches(termQuery(term, plan.Text)))
		if err != nil {
			return nil, nil, fmt.Errorf("catalog: searching text: %w", err)
		}
		for match, err := matches.Next(); match != nil || err != nil; match, err = matches.Next() {
			if err != nil {
				return nil, nil, fmt.Errorf("catalog: searching text: %w", err)
			}
			var id string
			page := -1
			if err := match.VisitStoredFields(func(field string, value []byte) bool {
				switch field {
				case fieldID:
					id = string(value)
				case fieldPage:
					page, _ = strconv.Atoi(string(value))
				}
				return id == "" || page < 0
			}); err != nil {
				return nil, nil, fmt.Errorf("catalog: reading a match: %w", err)
			}
			cand, ok := eligible[id]
			if !ok {
				continue
			}
			key := place{id, max(page, 0)}
			if onPage[key] == nil {
				onPage[key] = &found{}
			}
			onPage[key].score += match.Score * positionFactor(plan.Position, page, cand.item.PageCount)
			onPage[key].terms |= 1 << index
			inFile[id] |= 1 << index
		}
	}

	// The page kept for a file is the one holding the most of the words.
	bestOf := make(map[string]scored)
	held := make(map[string]int)
	for key, f := range onPage {
		count := bits.OnesCount64(f.terms)
		previous, seen := bestOf[key.id]
		if !seen || count > held[key.id] || (count == held[key.id] && f.score > previous.score) {
			bestOf[key.id] = scored{id: key.id, score: f.score, page: key.page}
			held[key.id] = count
		}
	}
	coverage := make(map[string]float64, len(inFile))
	list := make([]scored, 0, len(bestOf))
	for id, s := range bestOf {
		coverage[id] = float64(bits.OnesCount64(inFile[id])) / float64(len(terms))
		list = append(list, s)
	}
	slices.SortFunc(list, func(a, b scored) int {
		return cmp.Or(cmp.Compare(coverage[b.id], coverage[a.id]), cmp.Compare(b.score, a.score), cmp.Compare(a.id, b.id))
	})
	return list, coverage, nil
}

// positionFactor favours the pages where the user expects the words.
func positionFactor(position *query.PositionHint, page, pageCount int) float64 {
	if position == nil || page <= 0 {
		return 1
	}
	switch position.Where {
	case query.PositionEnd:
		if page >= max(pageCount-1, 1) {
			return 1.5
		}
	case query.PositionStart:
		if page <= 2 {
			return 1.5
		}
	case query.PositionPage:
		if page == position.Page {
			return 2
		}
	}
	return 1
}

// termQuery looks for one word under any of its variants, in the text or in
// the name of a file.
func termQuery(term query.Term, text *query.TextQuery) bluge.Query {
	phrase := func(words, field string, boost float64) bluge.Query {
		return bluge.NewMatchPhraseQuery(words).SetField(field).SetAnalyzer(french).SetSlop(1).SetBoost(boost)
	}
	any := bluge.NewBooleanQuery().SetMinShould(1)
	for i, variant := range append([]string{term.Value}, term.Alternatives...) {
		boost := 1.0
		if i > 0 {
			boost = 0.8
		}
		any.AddShould(phrase(variant, fieldBody, boost))
		// Words to read in a picture are not looked for in its name.
		if text.Scope != query.ScopeWritten {
			any.AddShould(phrase(variant, fieldName, boost))
		}
	}
	for _, unwanted := range text.Not {
		any.AddMustNot(phrase(unwanted, fieldBody, 1), phrase(unwanted, fieldName, 1))
	}
	return any
}

// applyHints adjusts scores with what was judged, not measured: a number of
// faces, a signature, a rough size. A hint moves a file up or down the list;
// it never removes it.
func applyHints(ranked []*candidate, plan query.Plan) {
	median := func(value func(*Item) int64) int64 {
		values := make([]int64, 0, len(ranked))
		for _, cand := range ranked {
			if v := value(cand.item); v > 0 {
				values = append(values, v)
			}
		}
		if len(values) == 0 {
			return 0
		}
		slices.Sort(values)
		return values[len(values)/2]
	}
	relative := func(hint *query.Hint, value func(*Item) int64) func(*Item) float64 {
		if hint == nil {
			return func(*Item) float64 { return 1 }
		}
		middle := median(value)
		return func(item *Item) float64 {
			v := value(item)
			if v == 0 || middle == 0 || v == middle {
				return 1
			}
			if (v > middle) == (hint.Level == query.LevelLarge) {
				return 1.15
			}
			return 0.9
		}
	}
	size := relative(plan.Size, func(item *Item) int64 { return item.Size })
	duration := relative(plan.Duration, func(item *Item) int64 { return int64(item.Seconds) })

	for _, cand := range ranked {
		item := cand.item
		factor := size(item) * duration(item)
		if people := plan.People; people != nil && item.Faces != nil {
			faces := *item.Faces
			gap := faces - people.Value
			if gap < 0 {
				gap = -gap
			}
			switch {
			case people.AtLeast && faces >= people.Value:
				factor *= 1.3
			case !people.AtLeast && gap == 0:
				factor *= 1.5
			case !people.AtLeast && gap <= people.Tolerance:
				factor *= 1.25
			default:
				factor *= 0.7
			}
		}
		if plan.HasFeature(query.FeaturePortrait) && item.Portrait {
			factor *= 1.3
		}
		if plan.HasFeature(query.FeatureSignature) && item.Signature {
			factor *= 1.3
		}
		cand.score *= factor
	}
}

// order sorts results: sure matches before uncertain ones, real matches before
// the ones shown beyond, then by what the user asked.
func order(ranked []*candidate, plan query.Plan) {
	// "The longest" is a duration for videos and recordings, a size otherwise.
	timed := len(ranked) > 0
	for _, cand := range ranked {
		if cand.item.Kind != query.KindVideo && cand.item.Kind != query.KindAudio {
			timed = false
		}
	}
	bulk := func(item *Item) int64 {
		if timed {
			return int64(item.Seconds)
		}
		return item.Size
	}
	slices.SortFunc(ranked, func(a, b *candidate) int {
		if a.beyond != b.beyond {
			if a.beyond {
				return 1
			}
			return -1
		}
		if a.tier != b.tier {
			return cmp.Compare(a.tier, b.tier)
		}
		newest := b.item.Date().Compare(a.item.Date())
		byID := cmp.Compare(a.item.ID, b.item.ID)
		switch plan.Sort {
		case query.SortNewest:
			return cmp.Or(newest, byID)
		case query.SortOldest:
			return cmp.Or(-newest, byID)
		case query.SortLargest:
			return cmp.Or(cmp.Compare(bulk(b.item), bulk(a.item)), newest, byID)
		case query.SortSmallest:
			return cmp.Or(cmp.Compare(bulk(a.item), bulk(b.item)), newest, byID)
		default:
			return cmp.Or(cmp.Compare(b.score, a.score), newest, byID)
		}
	})
}

// storedBody returns the text stored for one document of the index.
func storedBody(ctx context.Context, reader *bluge.Reader, docID string) string {
	matches, err := reader.Search(ctx, bluge.NewTopNSearch(1, bluge.NewTermQuery(docID).SetField("_id")))
	if err != nil {
		return ""
	}
	match, err := matches.Next()
	if err != nil || match == nil {
		return ""
	}
	var body string
	_ = match.VisitStoredFields(func(field string, value []byte) bool {
		if field == fieldBody {
			body = string(value)
			return false
		}
		return true
	})
	return body
}

// excerptRadius is the number of characters kept on each side of the words found.
const excerptRadius = 80

// excerpt cuts the text around the first of the words searched. Accents and
// case are ignored when looking for the words, and kept in what is returned.
func excerpt(body string, text *query.TextQuery) string {
	runes := []rune(strings.Join(strings.Fields(body), " "))
	if len(runes) == 0 {
		return ""
	}
	// folded holds one comparable letter per rune of the text.
	folded := make([]rune, len(runes))
	for i, r := range runes {
		f := []rune(query.Fold(string(r)))
		if len(f) == 0 {
			folded[i] = ' '
			continue
		}
		folded[i] = f[0]
		if !unicode.IsLetter(r) && !unicode.IsDigit(r) {
			folded[i] = ' '
		}
	}
	haystack := string(folded)
	at := -1
	for _, term := range text.Terms {
		for _, variant := range append([]string{term.Value}, term.Alternatives...) {
			needle := query.Fold(variant)
			if needle == "" {
				continue
			}
			// The stem of the word is enough: "facture" finds "factures".
			if index := strings.Index(haystack, needle); index >= 0 {
				position := len([]rune(haystack[:index]))
				if at < 0 || position < at {
					at = position
				}
			}
		}
	}
	if at < 0 {
		at = 0
	}
	start, end := max(at-excerptRadius, 0), min(at+excerptRadius, len(runes))
	out := strings.TrimSpace(string(runes[start:end]))
	if start > 0 {
		out = "…" + out
	}
	if end < len(runes) {
		out += "…"
	}
	return out
}
