package query

import (
	"slices"
	"strings"
	"time"
)

// Action is what a sentence does to the conversation.
type Action string

const (
	// ActionNew starts a new search.
	ActionNew Action = "new"
	// ActionRefine changes the current search.
	ActionRefine Action = "refine"
	// ActionSelect designates one of the results on screen.
	ActionSelect Action = "select"
	// ActionMore asks for the next results.
	ActionMore Action = "more"
	// ActionReset forgets the current search.
	ActionReset Action = "reset"
	// ActionNone means nothing was understood; the current search is kept.
	ActionNone Action = "none"
)

// Turn is the outcome of one sentence in a conversation.
type Turn struct {
	Action Action `json:"action"`
	// Plan is the search to run after this sentence.
	Plan Plan  `json:"plan"`
	Then *Plan `json:"then,omitempty"`
	// Index is the result designated by ActionSelect, starting at 1; -1 is the last one.
	Index int `json:"index,omitempty"`
}

// Session keeps the thread of a conversation. It holds no model and no text
// generation: a conversation is one search plan that each sentence replaces or
// adjusts, which keeps every answer instant and predictable.
type Session struct {
	parser    *Parser
	current   *Plan
	sentences []string
}

// NewSession starts an empty conversation.
func NewSession(parser *Parser) *Session {
	return &Session{parser: parser}
}

// Current returns the search in progress.
func (s *Session) Current() (Plan, bool) {
	if s.current == nil {
		return Plan{}, false
	}
	return s.current.Clone(), true
}

// Sentences returns what the user said since the search started. Validate
// needs them: a refined plan holds criteria coming from several sentences.
func (s *Session) Sentences() []string {
	return slices.Clone(s.sentences)
}

// Reset forgets the current search.
func (s *Session) Reset() {
	s.current, s.sentences = nil, nil
}

// refinement is how a sentence adjusts the current search.
type refinement int

const (
	refineNone refinement = iota
	// refineAdd adds criteria: "et en vidéo", "avec un chien".
	refineAdd
	// refineReplace swaps the subject: "plutôt une cuisine".
	refineReplace
	// refineRestrict narrows: "seulement les PDF".
	refineRestrict
	// refineNegate removes: "pas celui de l'an dernier", "sans les vidéos".
	refineNegate
)

var (
	negateCues   = wordSet("pas sans non sauf enleve retire supprime")
	restrictCues = wordSet("seulement uniquement juste que garde")
	replaceCues  = wordSet("plutot")
	addCues      = wordSet("et mais avec aussi pareil idem finalement")
	// keptCues open a refinement but belong to a rule: "plus récent", "avant 2023".
	keptCues = wordSet("plus moins avant apres")
)

var sameThing = compileAll("la|le meme chose?", "meme chose")

var resetPatterns = compileAll(
	"nouvelle recherche",
	"on? recommence|recommencer",
	"efface|oublie|annule tout|ca?",
	"repars|repartons de zero",
)

var moreSentences = wordSet("suivant suivants suivante suivantes encore continue")

var morePhrases = map[string]struct{}{
	"la suite": {}, "les suivants": {}, "page suivante": {}, "d autres": {}, "les autres": {},
	"encore plus": {}, "voir plus": {}, "montre la suite": {}, "affiche la suite": {}, "plus": {},
	"montre moi la suite": {}, "il y en a d autres": {},
}

var ordinals = map[string]int{
	"premier": 1, "premiere": 1, "deuxieme": 2, "second": 2, "seconde": 2, "troisieme": 3,
	"quatrieme": 4, "cinquieme": 5, "sixieme": 6, "septieme": 7, "huitieme": 8, "neuvieme": 9,
	"dixieme": 10, "dernier": -1, "derniere": -1,
}

var (
	selectLead  = wordSet("ouvre ouvrir montre affiche prends choisis moi le la l numero celui celle resultat")
	selectTrail = wordSet("resultat fichier stp svp")
)

// selection reads "le deuxième", "ouvre le 3", "le dernier".
func selection(toks []token) (int, bool) {
	from, to := 0, len(toks)
	for from < to && in(selectLead, toks[from].norm) {
		from++
	}
	for to > from && in(selectTrail, toks[to-1].norm) {
		to--
	}
	if to-from != 1 {
		return 0, false
	}
	word := toks[from].norm
	if index, ok := ordinals[word]; ok {
		return index, true
	}
	if isDigits(word) && len(word) <= 2 && from > 0 {
		n, _, _ := parseNumber(toks, from)
		return n, n > 0
	}
	return 0, false
}

// Interpret reads one sentence in the light of the current search.
func (s *Session) Interpret(sentence string, now time.Time) Turn {
	toks := tokenize(sentence)
	normalized := normalize(sentence)

	if length, _, _, ok := findFirst(resetPatterns, toks, 0); ok && length == len(toks) {
		s.Reset()
		return Turn{Action: ActionReset}
	}
	if s.current != nil {
		if _, ok := morePhrases[normalized]; ok || (len(toks) == 1 && in(moreSentences, toks[0].norm)) {
			return Turn{Action: ActionMore, Plan: s.current.Clone()}
		}
		if index, ok := selection(toks); ok {
			return Turn{Action: ActionSelect, Plan: s.current.Clone(), Index: index}
		}
	}

	mode, cleaned := readCues(sentence, toks)
	if s.current == nil {
		// Nothing to refine yet: the cue words are still dropped, so that
		// "plutôt un salon" searches a living room.
		mode = refineNone
	}
	a := s.parser.analyze(cleaned, now)
	understood := !a.plan.IsEmpty() || a.plan.Sort != SortRelevance || a.plan.Cardinality == CardinalityAll

	switch {
	case !understood:
		if s.current == nil {
			return Turn{Action: ActionNone}
		}
		return Turn{Action: ActionNone, Plan: s.current.Clone()}
	case s.current == nil:
		return s.start(sentence, a)
	case mode != refineNone:
		// An explicit cue: the sentence adjusts the current search.
	case a.hasCommand:
		return s.start(sentence, a)
	case a.description == "":
		// Only filters and no subject ("de l'an dernier", "en vidéo"): they
		// apply to what is already searched.
		mode = refineAdd
	default:
		return s.start(sentence, a)
	}

	next := refine(*s.current, a, mode)
	s.current = &next
	s.sentences = append(s.sentences, sentence)
	return Turn{Action: ActionRefine, Plan: next.Clone()}
}

func (s *Session) start(sentence string, a *analysis) Turn {
	plan := a.plan.Clone()
	s.current = &plan
	s.sentences = []string{sentence}
	return Turn{Action: ActionNew, Plan: a.plan, Then: a.then}
}

// readCues finds how the sentence relates to the current search, and blanks
// the cue words so that they are not read as part of a description. Blanking
// keeps every other word at its place in the sentence.
func readCues(sentence string, toks []token) (refinement, string) {
	mode := refineNone
	cleaned := []byte(sentence)
	blank := func(t token) {
		for k := t.start; k < t.end; k++ {
			cleaned[k] = ' '
		}
	}
	raise := func(m refinement) {
		if m > mode {
			mode = m
		}
	}

	i := 0
	for i < len(toks) {
		word := toks[i].norm
		switch {
		case in(negateCues, word):
			raise(refineNegate)
		case in(restrictCues, word):
			raise(refineRestrict)
		case in(replaceCues, word):
			raise(refineReplace)
		case in(addCues, word):
			raise(refineAdd)
		default:
			if length, _, _, ok := findFirst(sameThing, toks, i); ok {
				raise(refineAdd)
				for k := i; k < i+length; k++ {
					blank(toks[k])
				}
				i += length
				continue
			}
			if in(keptCues, word) {
				raise(refineAdd)
			}
			i = len(toks)
			continue
		}
		blank(toks[i])
		i++
	}

	// "montre seulement les PDF": the cue is not the first word.
	for _, t := range toks {
		if t.norm == "seulement" || t.norm == "uniquement" {
			raise(refineRestrict)
			blank(t)
		}
	}
	return mode, string(cleaned)
}

// refine applies what a sentence says to the current plan.
func refine(current Plan, a *analysis, mode refinement) Plan {
	next := current.Clone()
	said := a.plan

	if mode == refineNegate {
		if len(said.Kinds) > 0 {
			base := next.Kinds
			if len(base) == 0 {
				base = AllKinds
			}
			without := func(kinds []Kind) []Kind {
				return slices.DeleteFunc(slices.Clone(kinds), func(k Kind) bool { return slices.Contains(said.Kinds, k) })
			}
			next.Kinds = without(base)
			if len(next.Kinds) == 0 {
				// Removing the only kind searched must not mean "everything".
				next.Kinds = without(AllKinds)
			}
			next.KindsOrigin = clonePtr(said.KindsOrigin)
		}
		next.ExcludeDates = append(next.ExcludeDates, a.dates...)
		if said.Source != nil && next.Source != nil && said.Source.Source == next.Source.Source {
			next.Source = nil
		}
		next.Features = slices.DeleteFunc(next.Features, func(f Feature) bool { return said.HasFeature(f.Name) })
		if a.description != "" {
			if next.Visual != nil {
				next.Visual.Not = append(next.Visual.Not, a.description)
			}
			if next.Text != nil {
				for _, term := range a.terms {
					next.Text.Not = append(next.Text.Not, term.Value)
				}
			}
		}
		return next
	}

	if len(said.Kinds) > 0 {
		next.Kinds = slices.Clone(said.Kinds)
		next.KindsOrigin = clonePtr(said.KindsOrigin)
	}
	if said.Date != nil {
		next.Date = clonePtr(said.Date)
	}
	if said.Source != nil {
		next.Source = clonePtr(said.Source)
	}
	if said.Position != nil {
		next.Position = clonePtr(said.Position)
	}
	if said.Size != nil {
		next.Size = clonePtr(said.Size)
	}
	if said.Duration != nil {
		next.Duration = clonePtr(said.Duration)
	}
	if said.People != nil {
		next.People = clonePtr(said.People)
	}
	for _, f := range said.Features {
		if !next.HasFeature(f.Name) {
			next.Features = append(next.Features, f)
		}
	}
	if said.Sort != SortRelevance {
		next.Sort = said.Sort
	}
	if said.Cardinality == CardinalityAll {
		next.Cardinality = CardinalityAll
	}
	if said.Limit > 0 {
		next.Limit = said.Limit
	}

	switch {
	case a.description == "":
		// The subject is unchanged, but it may now apply to another kind of
		// file: "et en PDF ?" turns a visual description into words to find.
		if len(said.Kinds) > 0 {
			retarget(&next)
		}
	case mode == refineReplace || (next.Visual == nil && next.Text == nil):
		subject := *a
		subject.plan = next
		subject.plan.Visual, subject.plan.Text = nil, nil
		subject.applyDescription()
		next.Visual, next.Text = subject.plan.Visual, subject.plan.Text
	default:
		if next.Visual != nil {
			next.Visual.Description += ", " + a.description
			next.Visual.Origin = a.origin
		}
		if next.Text != nil {
			for _, term := range a.terms {
				if !slices.ContainsFunc(next.Text.Terms, func(t Term) bool { return normalize(t.Value) == normalize(term.Value) }) {
					next.Text.Terms = append(next.Text.Terms, term)
				}
			}
			next.Text.Phrase = strings.TrimSpace(next.Text.Phrase + ", " + a.description)
			next.Text.Origin = a.origin
		}
	}
	return next
}

// retarget moves the subject of a plan to where its new kinds are searched:
// pictures are compared with a description, documents with words.
func retarget(plan *Plan) {
	pictures := len(plan.Kinds) == 0 || plan.HasKind(KindImage) || plan.HasKind(KindVideo)
	documents := len(plan.Kinds) == 0 || plan.HasKind(KindPDF) || plan.HasKind(KindAudio)
	if plan.Text != nil && plan.Text.Scope == ScopeWritten {
		return
	}
	switch {
	case pictures && plan.Visual == nil && plan.Text != nil:
		plan.Visual = &Visual{Description: plan.Text.Phrase, Not: slices.Clone(plan.Text.Not), Origin: plan.Text.Origin}
		plan.Text.Weak = !documents
	case !pictures && plan.Visual != nil:
		if plan.Text != nil {
			plan.Text.Weak = false
		}
		plan.Visual = nil
	case pictures && plan.Text != nil:
		plan.Text.Weak = !documents
	}
}
