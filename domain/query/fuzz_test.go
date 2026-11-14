package query

import (
	"encoding/json"
	"os"
	"slices"
	"testing"

	"github.com/stretchr/testify/require"
)

// FuzzInterpret feeds arbitrary text to a conversation. Whatever is typed or
// dictated, the parser must not crash, and what it produces must hold up to
// its own check: origins inside the sentence, coherent dates.
func FuzzInterpret(f *testing.F) {
	for _, file := range []string{"phrases.json", "phrases_holdout.json", "phrases_unseen.json", "phrases_cible.json", "phrases_libres.json"} {
		data, err := os.ReadFile("testdata/" + file)
		require.NoError(f, err)
		var cases []evaluationCase
		require.NoError(f, json.Unmarshal(data, &cases))
		for _, c := range cases {
			f.Add(c.Sentence, "pas celle de l'an dernier")
		}
	}
	f.Add("il y a 999 ans avant entre et depuis", "plutôt page 0 le dernier")
	f.Add("et tous ceux", "sans les photos vidéos pdf audio")
	f.Add("\xff\xfe l'été dernier", "ouvre le 0")

	parser := NewParser()
	f.Fuzz(func(t *testing.T, first, second string) {
		// Whatever words a judge drops, even all of them or words of the other
		// sentence, the exact criteria read by the rules do not move.
		rules := parser.Parse(first, testNow).Plan
		for _, noise := range [][]string{parser.Candidates(first, testNow), parser.Candidates(second, testNow), {second}} {
			dropped := parser.ParseDropping(first, testNow, noise).Plan
			same := slices.Equal(rules.Kinds, dropped.Kinds) &&
				(rules.Date == nil) == (dropped.Date == nil) &&
				(rules.Source == nil) == (dropped.Source == nil) &&
				(rules.Place == nil) == (dropped.Place == nil) &&
				len(rules.Features) >= len(dropped.Features)
			if !same {
				t.Fatalf("dropping %q changed an exact criterion of %q:\n%s\n%s", noise, first, rules.Summary(), dropped.Summary())
			}
		}

		session := NewSession(parser)
		for _, sentence := range []string{first, second} {
			turn := session.Interpret(sentence, testNow)
			if turn.Action != ActionNew && turn.Action != ActionRefine {
				continue
			}
			for _, plan := range []*Plan{&turn.Plan, turn.Then} {
				if plan == nil {
					continue
				}
				for _, c := range plan.criteria() {
					if c.origin.Start < 0 || c.origin.End < c.origin.Start {
						t.Fatalf("origin out of order in %q: %+v", sentence, c)
					}
				}
				if d := plan.Date; d != nil && !d.From.Before(d.To) {
					t.Fatalf("empty period for %q: %v to %v", sentence, d.From, d.To)
				}
				if _, err := json.Marshal(plan); err != nil {
					t.Fatalf("plan of %q cannot be serialized: %v", sentence, err)
				}
			}
		}
	})
}
