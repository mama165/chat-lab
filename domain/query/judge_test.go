package query

import (
	"context"
	"errors"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// fakeJudge answers with fixed words and records what it was asked.
type fakeJudge struct {
	noise      []string
	err        error
	candidates []string
	calls      int
}

func (f *fakeJudge) Noise(_ context.Context, _ string, candidates []string) ([]string, error) {
	f.calls++
	f.candidates = candidates
	return f.noise, f.err
}

func TestParseWith_DropsWhatTheJudgeCallsNoise(t *testing.T) {
	sentence := "Il me faut absolument le PDF de mon bail"
	judge := &fakeJudge{noise: []string{"absolument"}}

	plan := NewParser().ParseWith(context.Background(), judge, sentence, testNow).Plan

	require.Equal(t, []string{"absolument", "bail"}, judge.candidates)
	require.Equal(t, "PDF · texte : bail", plan.Summary())
	require.Empty(t, Validate(plan, testNow, sentence))
}

func TestParseWith_TheJudgeCanOnlyRemoveOfferedWords(t *testing.T) {
	sentence := "le PDF de moins de 2 pages sur le bail de la maison, de 2024"
	rules := NewParser().Parse(sentence, testNow).Plan
	// The judge names the kind, the date and the bound: none of them is a
	// candidate, so nothing changes.
	judge := &fakeJudge{noise: []string{"PDF", "2024", "pages", "moins", "inventé"}}

	plan := NewParser().ParseWith(context.Background(), judge, sentence, testNow).Plan

	require.Equal(t, rules.Summary(), plan.Summary())
	require.Equal(t, []Kind{KindPDF}, plan.Kinds)
	require.NotNil(t, plan.Date)
	require.NotNil(t, plan.Pages)
}

func TestParseWith_FallsBackOnTheRules(t *testing.T) {
	parser := NewParser()
	sentence := "une photo floue d'un concert"
	rules := parser.Parse(sentence, testNow).Plan.Summary()

	cases := map[string]NoiseJudge{
		"no judge":                    nil,
		"judge in error":              &fakeJudge{err: errors.New("model not loaded"), noise: []string{"concert"}},
		"judge with nothing to say":   &fakeJudge{},
		"judge dropping every word":   &fakeJudge{noise: []string{"floue", "concert"}},
		"judge with unknown words":    &fakeJudge{noise: []string{"piano"}},
		"judge answering with spaces": &fakeJudge{noise: []string{" ", ""}},
	}
	for name, judge := range cases {
		plan := parser.ParseWith(context.Background(), judge, sentence, testNow).Plan
		require.Equal(t, rules, plan.Summary(), name)
	}
}

func TestParseWith_DoesNotAskAboutALoneWord(t *testing.T) {
	judge := &fakeJudge{noise: []string{"chien"}}

	plan := NewParser().ParseWith(context.Background(), judge, "les photos avec un chien", testNow).Plan

	require.Zero(t, judge.calls)
	require.Equal(t, "photos · image : un chien", plan.Summary())
}

func TestParse_Places(t *testing.T) {
	parser := NewParser()
	cases := map[string]string{
		"les photos prises au Mexique":           "Mexique",
		"les photos prises au mexique":           "mexique", // dictated, no capital
		"les vidéos de Lisbonne":                 "Lisbonne",
		"mes photos à New York":                  "New York",
		"les photos prises à Trouville":          "Trouville", // unknown name, capitalized
		"des photos en Nouvelle-Zélande":         "Nouvelle-Zélande",
		"les photos des États-Unis de l'an 2019": "États-Unis",
	}
	for sentence, place := range cases {
		plan := parser.Parse(sentence, testNow).Plan
		require.NotNil(t, plan.Place, sentence)
		require.Equal(t, place, plan.Place.Name, sentence)
		require.Nil(t, plan.Visual, sentence)
		require.Empty(t, Validate(plan, testNow, sentence), sentence)
	}

	for _, sentence := range []string{
		"une photo à la plage",      // a common noun is a description
		"une photo de nice paysage", // a known name needs "à", "au", "en" or a capital
		"le PDF en HD",              // an acronym is not a place
		"une facture de Ikea",       // a capital after "de" is not enough
		"des photos de réunion",
	} {
		require.Nil(t, parser.Parse(sentence, testNow).Plan.Place, sentence)
	}

	plan := NewParser(WithPlaces("Kerlouan")).Parse("les photos à kerlouan", testNow).Plan
	require.Equal(t, "kerlouan", plan.Place.Name)
	require.Equal(t, "photos · lieu : kerlouan", plan.Summary())
}

func TestParse_ExactDays(t *testing.T) {
	cases := []struct{ said, from, to string }{
		{"le 18 juin 2026", "2026-06-18", "2026-06-19"},
		{"le 18 juin", "2026-06-18", "2026-06-19"},
		// The 25th of December has not come yet this year.
		{"le 25 décembre", "2025-12-25", "2025-12-26"},
		{"le 1er janvier", "2026-01-01", "2026-01-02"},
		{"3 mars 2024", "2024-03-03", "2024-03-04"},
		{"entre le 1er et le 15 mai 2026", "2026-05-01", "2026-05-16"},
		{"du 3 au 10 août", "2026-08-03", "2026-08-11"},
		{"hier soir", "2026-11-13", "2026-11-14"},
	}
	for _, c := range cases {
		found := extractDates(c.said, tokenize(c.said), testNow)
		require.Len(t, found, 1, c.said)
		require.Equal(t, c.from, found[0].From.Format(time.DateOnly), c.said)
		require.Equal(t, c.to, found[0].To.Format(time.DateOnly), c.said)
	}
	// A day that does not exist is not a date.
	for _, said := range []string{"le 31 février 2026", "le 31 avril"} {
		for _, d := range extractDates(said, tokenize(said), testNow) {
			require.NotEqual(t, "date:day", d.Origin.Rule, said)
			require.NotEqual(t, "date:day-range", d.Origin.Rule, said)
		}
	}
}

func TestSession_TheDayCanBeChanged(t *testing.T) {
	session := NewSession(NewParser())
	turn := say(t, session, "Trouve-moi la vidéo prise le 18 juin 2026.", "plutôt le 19 juin")

	require.Equal(t, ActionRefine, turn.Action)
	require.Equal(t, "vidéos · le 19 juin 2026", turn.Plan.Summary())
}

func TestParse_BoundsExclusionsAndLanguage(t *testing.T) {
	parser := NewParser()
	summaries := map[string]string{
		"les vidéos de plus de 5 minutes":                "vidéos · plus de 5 minutes",
		"les vidéos de moins de 30 secondes":             "vidéos · moins de 30 secondes",
		"les vidéos de plus de 2 heures":                 "vidéos · plus de 2 heures",
		"les PDF de moins de 2 pages":                    "PDF · moins de 2 pages",
		"un PDF de 3 pages":                              "PDF · 3 pages",
		"un PDF d'une seule page":                        "PDF · 1 page",
		"les photos de la ville, pas celles de la plage": "photos · image : la ville · sans la plage",
		"les photos de l'été dernier sauf les selfies":   "photos · du 1er juin au 30 septembre 2026 · sans les selfies",
		"tout ce que j'ai sur Rome sauf les vidéos":      "photos · PDF · audio · image : Rome · texte : rome",
		"un café sans sucre en photo":                    "photos · image : un café · sans sucre",
		"les PDF en anglais":                             "PDF · en anglais",
		"les images qui contiennent du texte":            "photos · contient du texte",
		"vidéo de la plage, pas trop longue":             "vidéos · image : la plage · courte durée",
	}
	for sentence, summary := range summaries {
		plan := parser.Parse(sentence, testNow).Plan
		require.Equal(t, summary, plan.Summary(), sentence)
		require.Empty(t, Validate(plan, testNow, sentence), sentence)
	}
}
