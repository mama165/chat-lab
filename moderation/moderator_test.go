package moderation

import (
	"github.com/stretchr/testify/require"
	"testing"
)

// TestModerator_Censor
// The dictionary uses specific words to avoid partial collisions (e.g., "he" inside "The")
func TestModerator_Censor(t *testing.T) {
	req := require.New(t)
	dictionary := []string{"badger", "snake", "mushroom"}
	mod, err := NewModerator(dictionary)
	req.NoError(err)

	tests := []struct {
		name     string
		input    string
		expected string
	}{
		{
			name:     "Simple word and space preservation",
			input:    "The badger is here",
			expected: "The ****** is here",
		},
		{
			name:     "Multiple occurrences and preserved spacing",
			input:    "badger badger badger",
			expected: "****** ****** ******",
		},
		{
			name: "Leet speak and internal punctuation",
			// B (index 9) . 4 . d . g . € r (index 20) -> 10 characters
			input:    "Look at B.4.d.g.€r !",
			expected: "Look at ********** !",
		},
		{
			name:     "Uppercase and extreme noise",
			input:    "S-N-A-K-E is a B.A.D.G.E.R",
			expected: "********* is a ***********",
		},
		{
			name:     "Accents and special characters (UTF-8)",
			input:    "Un été avec un badger",
			expected: "Un été avec un ******",
		},
		{
			name:     "Word adjacent to trailing punctuation",
			input:    "I love badger!",
			expected: "I love ******!",
		},
		{
			name:     "Nothing to censor",
			input:    "Chat-Lab is amazing",
			expected: "Chat-Lab is amazing",
		},
		{
			name:     "Empty string",
			input:    "",
			expected: "",
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got := mod.Censor(tt.input)
			req.Equal(tt.expected, got)
		})
	}
}
