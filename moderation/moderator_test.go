package moderation

import (
	"log/slog"
	"testing"

	"github.com/mama165/sdk-go/logs"
	"github.com/stretchr/testify/require"
)

const replacementChar = '*'

// TestModerator_Censor
// The dictionary uses specific words to avoid partial collisions (e.g., "he" inside "The")
func TestModerator_Censor(t *testing.T) {
	req := require.New(t)
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	dictionary := []string{"badger", "snake", "mushroom"}
	mod, err := NewModerator(dictionary, replacementChar, log)
	req.NoError(err)

	tests := []struct {
		name     string
		input    string
		expected string
		words    []string
	}{
		{
			name:     "Simple word and space preservation",
			input:    "The badger is here",
			expected: "The ****** is here",
			words:    []string{"badger"},
		},
		{
			name:     "Multiple occurrences and preserved spacing",
			input:    "badger badger badger",
			expected: "****** ****** ******",
			words:    []string{"badger", "badger", "badger"},
		},
		{
			name: "Leet speak and internal punctuation",
			// B (index 9) . 4 . d . g . € r (index 20) -> 10 characters
			input:    "Look at B.4.d.g.€r !",
			expected: "Look at ********** !",
			words:    []string{"badger"},
		},
		{
			name:     "Uppercase and extreme noise",
			input:    "S-N-A-K-E is a B.A.D.G.E.R",
			expected: "********* is a ***********",
			words:    []string{"snake", "badger"},
		},
		{
			name:     "Accents and special characters (UTF-8)",
			input:    "Un été avec un badger",
			expected: "Un été avec un ******",
			words:    []string{"badger"},
		},
		{
			name:     "Word adjacent to trailing punctuation",
			input:    "I love badger!",
			expected: "I love ******!",
			words:    []string{"badger"},
		},
		{
			name:     "Nothing to censor",
			input:    "Chat-Lab is amazing",
			expected: "Chat-Lab is amazing",
			words:    nil,
		},
		{
			name:     "Empty string",
			input:    "",
			expected: "",
			words:    nil,
		},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			content, words := mod.Censor(tt.input)
			req.Equal(tt.expected, content, "test=%s,", tt.name)
			req.Equal(tt.words, words, "expected=%s,words=%s", tt.expected, words)
		})
	}
}

func TestModerator_CornerCases(t *testing.T) {
	req := require.New(t)
	log := logs.GetLoggerFromLevel(slog.LevelDebug)

	// Given real noise and not Leet Speak associated
	dictionary := []string{"...", ",,,", "", "badger"}

	mod, err := NewModerator(dictionary, replacementChar, log)
	req.NoError(err)

	// Then the sentence is censored
	input := "The badger is safe"
	expected := "The ****** is safe"
	content, words := mod.Censor(input)
	req.Equal(expected, content)
	req.Equal([]string{"badger"}, words)

	// Then real noise is uncensored
	input = "Hello ..."
	expected = "Hello ..."
	content, words = mod.Censor(input)
	req.Equal(expected, content)
	req.Nil(words)
}
