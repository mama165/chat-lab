package query

import (
	"encoding/json"
	"os"
	"testing"

	"github.com/stretchr/testify/require"
)

// phrases_cible.json holds the sentences of the owner, with the plan expected
// for each. It is the reference for measuring a language model later, so it
// is not part of TestEvaluationSet: today's rules do not understand place,
// exact days, page counts or exclusions, and that gap is what the model must
// close. The test below only checks the file itself.
func TestTargetSetIsWellFormed(t *testing.T) {
	data, err := os.ReadFile("testdata/phrases_cible.json")
	require.NoError(t, err)
	var cases []struct {
		Sentence string         `json:"sentence"`
		Want     map[string]any `json:"want"`
	}
	require.NoError(t, json.Unmarshal(data, &cases))
	require.GreaterOrEqual(t, len(cases), 30)
	for _, c := range cases {
		require.NotEmpty(t, c.Sentence)
		require.NotEmpty(t, c.Want, c.Sentence)
	}
}
