package query

import (
	"encoding/json"
	"fmt"
	"os"
	"slices"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// testNow is a Saturday; every relative date in the tests is computed from it.
var testNow = time.Date(2026, time.November, 14, 12, 0, 0, 0, time.UTC)

// expectation lists what a sentence must be understood as. Only the fields
// that are set are compared.
type expectation struct {
	Kinds         *[]Kind  `json:"kinds"`
	Visual        *string  `json:"visual"`
	NoVisual      bool     `json:"no_visual"`
	Terms         []string `json:"terms"`
	Scope         string   `json:"scope"`
	WeakText      bool     `json:"weak_text"`
	NoText        bool     `json:"no_text"`
	Date          []string `json:"date"`
	NoDate        bool     `json:"no_date"`
	People        *int     `json:"people"`
	PeopleAtLeast bool     `json:"people_at_least"`
	Features      []string `json:"features"`
	Size          string   `json:"size"`
	NoSize        bool     `json:"no_size"`
	Duration      string   `json:"duration"`
	Position      string   `json:"position"`
	Source        string   `json:"source"`
	Sort          string   `json:"sort"`
	Cardinality   string   `json:"cardinality"`
	Then          bool     `json:"then"`
	Place         *string  `json:"place"`
	NoPlace       bool     `json:"no_place"`
	PagesMin      *int     `json:"pages_min"`
	PagesMax      *int     `json:"pages_max"`
	SecondsMin    *int     `json:"seconds_min"`
	SecondsMax    *int     `json:"seconds_max"`
	Exclude       []string `json:"exclude"`
	Language      string   `json:"language"`
}

type evaluationCase struct {
	Sentence string `json:"sentence"`
	// Must marks the sentences the owner of the project wrote himself: none of
	// them may fail.
	Must bool        `json:"must"`
	Want expectation `json:"want"`
}

// differences compares a result with what was expected.
func differences(result Result, want expectation) []string {
	var diffs []string
	fail := func(format string, args ...any) { diffs = append(diffs, fmt.Sprintf(format, args...)) }
	plan := result.Plan

	if want.Kinds != nil && !slices.Equal(plan.Kinds, *want.Kinds) && (len(plan.Kinds) > 0 || len(*want.Kinds) > 0) {
		fail("kinds: got %v, want %v", plan.Kinds, *want.Kinds)
	}
	if want.Visual != nil {
		if plan.Visual == nil {
			fail("visual: got none, want %q", *want.Visual)
		} else if plan.Visual.Description != *want.Visual {
			fail("visual: got %q, want %q", plan.Visual.Description, *want.Visual)
		}
	}
	if want.NoVisual && plan.Visual != nil {
		fail("visual: got %q, want none", plan.Visual.Description)
	}
	if want.Terms != nil {
		var got []string
		if plan.Text != nil {
			for _, term := range plan.Text.Terms {
				got = append(got, term.Value)
			}
		}
		if !slices.Equal(got, want.Terms) {
			fail("terms: got %v, want %v", got, want.Terms)
		}
	}
	if want.Scope != "" && (plan.Text == nil || string(plan.Text.Scope) != want.Scope) {
		fail("scope: want %q", want.Scope)
	}
	if want.WeakText && (plan.Text == nil || !plan.Text.Weak) {
		fail("text: want weak words")
	}
	if want.NoText && plan.Text != nil {
		fail("text: got %v, want none", plan.Text.Terms)
	}
	if want.Date != nil {
		if plan.Date == nil {
			fail("date: got none, want %v", want.Date)
		} else if got := []string{plan.Date.From.Format(time.DateOnly), plan.Date.To.Format(time.DateOnly)}; !slices.Equal(got, want.Date) {
			fail("date: got %v, want %v", got, want.Date)
		}
	}
	if want.NoDate && plan.Date != nil {
		fail("date: got %q, want none", plan.Date.Label)
	}
	if want.People != nil {
		switch {
		case plan.People == nil:
			fail("people: got none, want %d", *want.People)
		case plan.People.Value != *want.People || plan.People.AtLeast != want.PeopleAtLeast:
			fail("people: got %d (at least: %v), want %d (at least: %v)",
				plan.People.Value, plan.People.AtLeast, *want.People, want.PeopleAtLeast)
		}
	}
	for _, name := range want.Features {
		if !plan.HasFeature(FeatureName(name)) {
			fail("features: %q is missing", name)
		}
	}
	level := func(field string, hint *Hint, wanted string) {
		if wanted != "" && (hint == nil || string(hint.Level) != wanted) {
			fail("%s: want %q", field, wanted)
		}
	}
	level("size", plan.Size, want.Size)
	level("duration", plan.Duration, want.Duration)
	if want.NoSize && plan.Size != nil {
		fail("size: got %q, want none", plan.Size.Level)
	}
	if want.Position != "" && (plan.Position == nil || string(plan.Position.Where) != want.Position) {
		fail("position: want %q", want.Position)
	}
	if want.Source != "" && (plan.Source == nil || string(plan.Source.Source) != want.Source) {
		fail("source: want %q", want.Source)
	}
	if want.Sort != "" && string(plan.Sort) != want.Sort {
		fail("sort: got %q, want %q", plan.Sort, want.Sort)
	}
	if want.Cardinality != "" && string(plan.Cardinality) != want.Cardinality {
		fail("cardinality: got %q, want %q", plan.Cardinality, want.Cardinality)
	}
	if want.Place != nil && (plan.Place == nil || plan.Place.Name != *want.Place) {
		fail("place: got %+v, want %q", plan.Place, *want.Place)
	}
	if want.NoPlace && plan.Place != nil {
		fail("place: got %q, want none", plan.Place.Name)
	}
	bound := func(field string, got *Bound, min, max *int) {
		if min == nil && max == nil {
			return
		}
		if got == nil {
			fail("%s: got none", field)
			return
		}
		if min != nil && got.Min != *min {
			fail("%s: got min %d, want %d", field, got.Min, *min)
		}
		if max != nil && got.Max != *max {
			fail("%s: got max %d, want %d", field, got.Max, *max)
		}
	}
	bound("pages", plan.Pages, want.PagesMin, want.PagesMax)
	bound("seconds", plan.Seconds, want.SecondsMin, want.SecondsMax)
	if want.Exclude != nil {
		var got []string
		if plan.Visual != nil {
			got = plan.Visual.Not
		}
		if !slices.Equal(got, want.Exclude) {
			fail("exclude: got %v, want %v", got, want.Exclude)
		}
	}
	if want.Language != "" && (plan.Language == nil || plan.Language.Code != want.Language) {
		fail("language: want %q", want.Language)
	}
	if want.Then && result.Then == nil {
		fail("then: the second search is missing")
	}
	return diffs
}

// TestEvaluationSet measures the parser on real sentences.
//
// phrases.json holds the sentences the rules were first written for, among
// them the ones the owner of the project wrote himself: none of those may fail.
// The two other files were each written after the rules, run once, and only
// then used to improve them. Their first-run scores are the honest measure of
// how the parser does on wordings it has never seen: 13/25, then 11/20, with
// the kind of file right every time and most misses being one extra word in
// the description. They now guard against regressions; a new measure needs
// new sentences.
func TestEvaluationSet(t *testing.T) {
	for _, file := range []string{"phrases.json", "phrases_holdout.json", "phrases_unseen.json", "phrases_cible.json"} {
		t.Run(file, func(t *testing.T) {
			data, err := os.ReadFile("testdata/" + file)
			require.NoError(t, err)
			var cases []evaluationCase
			require.NoError(t, json.Unmarshal(data, &cases))
			require.GreaterOrEqual(t, len(cases), 20)

			parser := NewParser()
			passed := 0
			for _, c := range cases {
				result := parser.Parse(c.Sentence, testNow)
				diffs := differences(result, c.Want)
				if len(diffs) == 0 {
					passed++
				} else {
					t.Logf("%q\n  understood as: %s\n  %v", c.Sentence, result.Plan.Summary(), diffs)
				}
				if c.Must {
					require.Empty(t, diffs, "sentence written by the owner: %q", c.Sentence)
				}
				// Whatever the parser produces must hold up to its own check.
				require.Empty(t, Validate(result.Plan, testNow, c.Sentence), "plan of %q", c.Sentence)
				if result.Then != nil {
					require.Empty(t, Validate(*result.Then, testNow, c.Sentence), "second plan of %q", c.Sentence)
				}
			}
			score := float64(passed) / float64(len(cases))
			t.Logf("%s: %d/%d sentences fully understood (%.0f %%)", file, passed, len(cases), 100*score)
			// Below 90 %, a language model would be worth its cost.
			require.GreaterOrEqual(t, score, 0.9)
		})
	}
}

func TestParse_SecondSearchIsWiderThanTheFirst(t *testing.T) {
	result := NewParser().Parse("Trouve-moi le QR code que j'ai téléchargé il y a un mois, et tous ceux qui sont dans ma bibliothèque.", testNow)

	require.NotNil(t, result.Plan.Date)
	require.NotNil(t, result.Plan.Source)
	require.NotNil(t, result.Then)
	require.True(t, result.Then.HasFeature(FeatureCode))
	require.Nil(t, result.Then.Date)
	require.Nil(t, result.Then.Source)
	require.Equal(t, CardinalityAll, result.Then.Cardinality)
}

func TestParse_WordsOfTheLexiconCarryTheirVariants(t *testing.T) {
	result := NewParser().Parse("le PDF de mon fournisseur d'électricité", testNow)

	require.NotNil(t, result.Plan.Text)
	require.Len(t, result.Plan.Text.Terms, 1)
	require.Equal(t, "électricité", result.Plan.Text.Terms[0].Value)
	require.Contains(t, result.Plan.Text.Terms[0].Alternatives, "edf")
}

func TestParse_PersonalLexicon(t *testing.T) {
	lexicon := DefaultLexicon()
	lexicon.Add("mon assureur", "assurance", "maif")

	result := NewParser(WithLexicon(lexicon)).Parse("le dernier PDF de mon assureur", testNow)

	require.NotNil(t, result.Plan.Text)
	require.Equal(t, []Term{{Value: "assurance", Alternatives: []string{"maif"}}}, result.Plan.Text.Terms)
	require.Equal(t, SortNewest, result.Plan.Sort)
}

func TestParse_OriginsPointIntoTheSentence(t *testing.T) {
	sentence := "Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle."
	plan := NewParser().Parse(sentence, testNow).Plan

	require.Equal(t, "ces 6 derniers mois", plan.Date.Origin.Text)
	require.Equal(t, plan.Date.Origin.Text, sentence[plan.Date.Origin.Start:plan.Date.Origin.End])
	require.Equal(t, "PDF", plan.KindsOrigin.Text)
	require.Equal(t, "la notice d'un lave-vaisselle", plan.Text.Origin.Text)
}

func TestParse_EmptyAndMeaninglessSentences(t *testing.T) {
	parser := NewParser()
	for _, sentence := range []string{"", "   ", "?!", "trouve-moi", "s'il te plaît"} {
		plan := parser.Parse(sentence, testNow).Plan
		require.True(t, plan.IsEmpty(), "sentence %q", sentence)
	}
}

func TestParse_AccentsAndCaseDoNotMatter(t *testing.T) {
	parser := NewParser()
	reference := parser.Parse("trouve-moi la vidéo de l'été dernier", testNow).Plan
	// The same sentence typed without accents, then with accents written as
	// separate combining characters, as some keyboards do.
	for _, variant := range []string{"TROUVE-MOI LA VIDEO DE L'ETE DERNIER", "trouve-moi la vidéo de l'été dernier"} {
		plan := parser.Parse(variant, testNow).Plan
		require.Equal(t, reference.Kinds, plan.Kinds, variant)
		require.NotNil(t, plan.Date, variant)
		require.Equal(t, reference.Date.From, plan.Date.From, variant)
	}
}

func TestParse_DetailsOfARequest(t *testing.T) {
	parser := NewParser()

	plan := parser.Parse("les trois dernières vidéos", testNow).Plan
	require.Equal(t, 3, plan.Limit)
	require.Equal(t, SortNewest, plan.Sort)
	require.Equal(t, "vidéos · plus récents d'abord · 3 résultats", plan.Summary())

	plan = parser.Parse("ce pdf avec un tableau de prix à la deuxième page", testNow).Plan
	require.Equal(t, PositionPage, plan.Position.Where)
	require.Equal(t, 2, plan.Position.Page)
	require.Equal(t, "à la deuxième page", plan.Position.Origin.Text)

	plan = parser.Parse("le contrat, page 12", testNow).Plan
	require.Equal(t, 12, plan.Position.Page)

	plan = parser.Parse("vidéos du concert de samedi dernier", testNow).Plan
	require.Equal(t, "le 7 novembre 2026", plan.Date.Label)

	// A size or a duration is only read next to the kind of file.
	plan = parser.Parse("la photo d'une longue table", testNow).Plan
	require.Nil(t, plan.Size)
	require.Nil(t, plan.Duration)
	require.Equal(t, "une longue table", plan.Visual.Description)
}

func TestTokenize(t *testing.T) {
	toks := tokenize("L'an dernier, un lave-vaisselle à 300 € !")
	words := make([]string, len(toks))
	for i, tok := range toks {
		words[i] = tok.norm
	}
	require.Equal(t, []string{"l", "an", "dernier", "un", "lave", "vaisselle", "a", "300"}, words)
}

func TestPlan_CloneIsIndependent(t *testing.T) {
	original := NewParser().Parse("les photos d'une rose de l'an dernier", testNow).Plan
	clone := original.Clone()

	clone.Kinds[0] = KindVideo
	clone.Visual.Description = "autre chose"
	clone.Text.Terms[0].Value = "autre"
	clone.Date.Label = "autre"

	require.Equal(t, KindImage, original.Kinds[0])
	require.Equal(t, "une rose", original.Visual.Description)
	require.Equal(t, "rose", original.Text.Terms[0].Value)
	require.Equal(t, "2025", original.Date.Label)
}

func TestPlan_Summary(t *testing.T) {
	plan := NewParser().Parse("Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle.", testNow).Plan
	require.Equal(t, "PDF · depuis le 26 avril 2026 · texte : notice, lave-vaisselle", plan.Summary())

	plan = NewParser().Parse("Trouve-moi des photos de portraits où on voit un homme avec une femme.", testNow).Plan
	require.Equal(t, "photos · portrait · image : portraits, un homme avec une femme · environ 2 personnes", plan.Summary())
}

func BenchmarkParse(b *testing.B) {
	parser := NewParser()
	for b.Loop() {
		parser.Parse("Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle.", testNow)
	}
}
