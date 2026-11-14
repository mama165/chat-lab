package query

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/stretchr/testify/require"
)

// FuzzInterpret feeds arbitrary text to a conversation. Whatever is typed or
// dictated, the parser must not crash, and what it produces must hold up to
// its own check: origins inside the sentence, coherent dates.
func FuzzInterpret(f *testing.F) {
	for _, file := range []string{"phrases.json", "phrases_holdout.json", "phrases_unseen.json"} {
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
