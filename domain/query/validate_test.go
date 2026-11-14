package query

import (
	"encoding/json"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func codes(issues []Issue) []IssueCode {
	out := make([]IssueCode, len(issues))
	for i, issue := range issues {
		out[i] = issue.Code
	}
	return out
}

func TestValidate_RefusesAnInventedFilter(t *testing.T) {
	sentence := "Trouve-moi le PDF où il est question d'un notaire."
	plan := NewParser().Parse(sentence, testNow).Plan
	require.Empty(t, Validate(plan, testNow, sentence))

	// What a language model typically adds on its own: a period nobody gave.
	plan.Date = &DateRange{
		From: testNow.AddDate(-1, 0, 0), To: testNow, Label: "depuis un an",
		Origin: Origin{Text: "l'an dernier"},
	}

	issues := Validate(plan, testNow, sentence)
	require.Equal(t, []IssueCode{IssueUntraceable}, codes(issues))
	require.Equal(t, "date", issues[0].Field)
}

func TestValidate_RefusesACriterionWithoutOrigin(t *testing.T) {
	sentence := "une photo de cuisine"
	plan := NewParser().Parse(sentence, testNow).Plan
	plan.People = &Count{Value: 3}

	issues := Validate(plan, testNow, sentence)
	require.Equal(t, []IssueCode{IssueUntraceable}, codes(issues))
	require.Equal(t, "people", issues[0].Field)
}

func TestValidate_Values(t *testing.T) {
	origin := Origin{Text: "cuisine"}
	base := func() Plan {
		return Plan{
			Visual: &Visual{Description: "cuisine", Origin: origin},
			Sort:   SortRelevance, Cardinality: CardinalityMany,
		}
	}
	cases := []struct {
		name   string
		change func(p *Plan)
		code   IssueCode
	}{
		{"empty plan", func(p *Plan) { p.Visual = nil }, IssueEmpty},
		{"unknown kind", func(p *Plan) { p.Kinds = []Kind{"spreadsheet"}; p.KindsOrigin = &origin }, IssueUnknownValue},
		{"inverted period", func(p *Plan) {
			p.Date = &DateRange{From: testNow, To: testNow.AddDate(0, 0, -1), Origin: origin}
		}, IssueDateInverted},
		{"future period", func(p *Plan) {
			p.Date = &DateRange{From: testNow.AddDate(0, 1, 0), To: testNow.AddDate(0, 2, 0), Origin: origin}
		}, IssueDateFuture},
		{"period before any file", func(p *Plan) {
			p.Date = &DateRange{From: time.Date(1950, 1, 1, 0, 0, 0, 0, time.UTC), To: time.Date(1960, 1, 1, 0, 0, 0, 0, time.UTC), Origin: origin}
		}, IssueDateTooOld},
		{"blank description", func(p *Plan) { p.Visual.Description = "  " }, IssueEmpty},
		{"endless description", func(p *Plan) { p.Visual.Description = strings.Repeat("é", 300) }, IssueTooLong},
		{"text without words", func(p *Plan) { p.Text = &TextQuery{Scope: ScopeAny, Origin: origin} }, IssueEmpty},
		{"unknown scope", func(p *Plan) {
			p.Text = &TextQuery{Terms: []Term{{Value: "cuisine"}}, Scope: "title", Origin: origin}
		}, IssueUnknownValue},
		{"absurd count", func(p *Plan) { p.People = &Count{Value: 5000, Origin: origin} }, IssueCountRange},
		{"unknown feature", func(p *Plan) { p.Features = []Feature{{Name: "watermark", Origin: origin}} }, IssueUnknownValue},
		{"unknown size", func(p *Plan) { p.Size = &Hint{Level: "huge", Origin: origin} }, IssueUnknownValue},
		{"unknown position", func(p *Plan) { p.Position = &PositionHint{Where: "middle", Origin: origin} }, IssueUnknownValue},
		{"unknown source", func(p *Plan) { p.Source = &SourceFilter{Source: "cloud", Origin: origin} }, IssueUnknownValue},
		{"unknown sort", func(p *Plan) { p.Sort = "random" }, IssueUnknownValue},
		{"unknown cardinality", func(p *Plan) { p.Cardinality = "two" }, IssueUnknownValue},
	}
	require.Empty(t, Validate(base(), testNow, "cuisine"))
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			plan := base()
			c.change(&plan)
			require.Contains(t, codes(Validate(plan, testNow, "cuisine")), c.code)
		})
	}
}

func TestPlan_JSONRoundTrip(t *testing.T) {
	sentence := "Trouve-moi le QR code que j'ai téléchargé il y a un mois, et tous ceux qui sont dans ma bibliothèque."
	original := NewParser().Parse(sentence, testNow)

	data, err := json.Marshal(original)
	require.NoError(t, err)
	var decoded Result
	require.NoError(t, json.Unmarshal(data, &decoded))

	require.Equal(t, original.Plan.Summary(), decoded.Plan.Summary())
	require.True(t, original.Plan.Date.From.Equal(decoded.Plan.Date.From))
	require.Equal(t, original.Plan.Features, decoded.Plan.Features)
	require.Equal(t, original.Then.Summary(), decoded.Then.Summary())
}

func TestParseJSON(t *testing.T) {
	sentence := "le PDF où il est question d'un notaire"

	t.Run("accepts a plan every word of which was said", func(t *testing.T) {
		data, err := json.Marshal(NewParser().Parse(sentence, testNow).Plan)
		require.NoError(t, err)

		plan, issues, err := ParseJSON(data, testNow, sentence)
		require.NoError(t, err)
		require.Empty(t, issues)
		require.Equal(t, "PDF · texte : notaire", plan.Summary())
	})

	t.Run("fills the defaults a model may leave out", func(t *testing.T) {
		plan, _, err := ParseJSON([]byte(`{"kinds":["pdf"],"kinds_origin":{"text":"PDF"}}`), testNow, sentence)
		require.NoError(t, err)
		require.Equal(t, SortRelevance, plan.Sort)
		require.Equal(t, CardinalityMany, plan.Cardinality)
	})

	t.Run("refuses an invented filter", func(t *testing.T) {
		data := []byte(`{"kinds":["pdf"],"kinds_origin":{"text":"PDF"},
			"source":{"source":"download","origin":{"text":"téléchargé"}}}`)
		_, issues, err := ParseJSON(data, testNow, sentence)
		require.Error(t, err)
		require.Equal(t, []IssueCode{IssueUntraceable}, codes(issues))
	})

	t.Run("refuses a field that does not exist", func(t *testing.T) {
		_, _, err := ParseJSON([]byte(`{"kinds":["pdf"],"owner":"moi"}`), testNow, sentence)
		require.Error(t, err)
	})

	t.Run("refuses what is not JSON", func(t *testing.T) {
		_, _, err := ParseJSON([]byte(`Voici le plan : PDF`), testNow, sentence)
		require.Error(t, err)
	})
}

func TestRelax_LoosensOneFilterAtATime(t *testing.T) {
	sentence := "le PDF téléchargé il y a un mois, sur les impôts"
	plan := NewParser().Parse(sentence, testNow).Plan
	require.NotNil(t, plan.Source)
	require.NotNil(t, plan.Date)

	var steps []RelaxStep
	for {
		next, relaxation, ok := Relax(plan, testNow)
		if !ok {
			break
		}
		require.NotEmpty(t, relaxation.Message)
		require.Empty(t, Validate(next, testNow, sentence))
		steps = append(steps, relaxation.Step)
		plan = next
	}

	require.Equal(t, []RelaxStep{RelaxSource, RelaxDateWidened, RelaxDateDropped}, steps)
	// What the user is after is never given up.
	require.Equal(t, []Kind{KindPDF}, plan.Kinds)
	require.Equal(t, "PDF · texte : impôts", plan.Summary())
}

func TestRelax_WidensThePeriodOnBothSides(t *testing.T) {
	plan := NewParser().Parse("les photos d'il y a un mois", testNow).Plan

	next, relaxation, ok := Relax(plan, testNow)

	require.True(t, ok)
	require.Equal(t, RelaxDateWidened, relaxation.Step)
	require.True(t, next.Date.From.Before(plan.Date.From))
	require.True(t, next.Date.To.After(plan.Date.To))
	require.False(t, next.Date.To.After(tomorrow(testNow)))
	// The plan given to Relax is left untouched.
	require.False(t, plan.Date.Widened)
}

func TestRelax_WrittenWordsFallBackOnWhatPicturesShow(t *testing.T) {
	plan := NewParser().Parse("une image de mot herbe qui apparaît", testNow).Plan
	require.Nil(t, plan.Visual)

	next, relaxation, ok := Relax(plan, testNow)

	require.True(t, ok)
	require.Equal(t, RelaxWritten, relaxation.Step)
	require.Equal(t, "herbe", next.Visual.Description)
	_, _, again := Relax(next, testNow)
	require.False(t, again)
}

func TestRelax_NeverDropsTheKindOrTheCode(t *testing.T) {
	plan := NewParser().Parse("les QR codes en photo", testNow).Plan

	_, _, ok := Relax(plan, testNow)

	require.False(t, ok)
}

func TestLexicon_Load(t *testing.T) {
	lexicon := DefaultLexicon()
	err := lexicon.Load(strings.NewReader(`[
		{"phrase": "ma banque", "value": "banque", "alternatives": ["crédit mutuel"]},
		{"phrase": "électricité", "alternatives": ["ekwateur"]}
	]`))
	require.NoError(t, err)
	parser := NewParser(WithLexicon(lexicon))

	terms := parser.Parse("le PDF de ma banque", testNow).Plan.Text.Terms
	require.Equal(t, []Term{{Value: "banque", Alternatives: []string{"crédit mutuel"}}}, terms)

	// A personal entry replaces the default one for the same expression.
	terms = parser.Parse("le PDF sur l'électricité", testNow).Plan.Text.Terms
	require.Equal(t, []Term{{Value: "électricité", Alternatives: []string{"ekwateur"}}}, terms)

	require.Error(t, lexicon.Load(strings.NewReader(`{"phrase": "pas une liste"}`)))
}

func TestValidate_CriterionMustBeNamedByItsWords(t *testing.T) {
	sentence := "trouve le QR code du mois dernier"
	plan := NewParser().Parse(sentence, testNow).Plan
	require.Empty(t, Validate(plan, testNow, sentence))

	// Traced to words that were said, but not naming the feature: refused.
	plan.Features = append(plan.Features, Feature{Name: FeatureSignature, Origin: Origin{Text: "QR code"}})
	issues := Validate(plan, testNow, sentence)
	require.Equal(t, []IssueCode{IssueUnjustified}, codes(issues))
}
