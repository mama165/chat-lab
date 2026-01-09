package moderation

import (
	"chat-lab/errors"
	"context"
	"log/slog"
	"unicode"

	goahocorasick "github.com/anknown/ahocorasick"
)

type Moderator struct {
	matcher      *goahocorasick.Machine
	censoredChar rune
	log          *slog.Logger
}

type TextMapping struct {
	Normalized []rune
	OrigIdx    []int
}

// NewModerator initializes the Aho-Corasick automaton with a normalized version of the provided censored words list.
func NewModerator(censoredWords []string, censoredChar rune, log *slog.Logger) (Moderator, error) {
	patterns := make([][]rune, 0, len(censoredWords)) // Change la taille initiale à 0
	for _, word := range censoredWords {
		normalized := normalizeRunes([]rune(word))
		if len(normalized) > 0 { // <--- Sécurité cruciale
			patterns = append(patterns, normalized)
		}
	}

	if len(patterns) == 0 {
		return Moderator{}, errors.ErrNoValidPatterns
	}

	m := new(goahocorasick.Machine)
	if err := m.Build(patterns); err != nil {
		return Moderator{}, err
	}
	return Moderator{matcher: m, censoredChar: censoredChar, log: log}, nil
}

// Censor identifies forbidden patterns in a normalized version of the text
// but applies the replacement (stars) on the original string's runes.
// It uses a mapping (OrigIdx) to bridge the gap between the normalized
// coordinates (where noise and leet-speak are removed) and the
// actual positions in the user's original message.
func (m *Moderator) Censor(original string) (string, []string) {
	isDebug := m.log.Enabled(context.Background(), slog.LevelDebug)

	mapping := m.normalize(original)
	if len(mapping.Normalized) == 0 {
		return original, nil
	}

	origRunes := []rune(original)
	matches := m.matcher.MultiPatternSearch(mapping.Normalized, false)

	if isDebug {
		m.log.Debug("matches detected", "count", len(matches))
	}

	if len(matches) == 0 {
		return original, nil
	}

	// Prepare a slice to collect the words found for telemetry
	foundWords := make([]string, 0, len(matches))

	for _, match := range matches {
		normStart := match.Pos
		normEnd := normStart + len(match.Word)

		if normStart < 0 || normEnd > len(mapping.OrigIdx) {
			continue
		}

		// We collect the normalized word (e.g., "idiot" even if written "1d|0t")
		foundWords = append(foundWords, string(match.Word))

		origStart := mapping.OrigIdx[normStart]
		lastCharOrigIdx := mapping.OrigIdx[normEnd-1]
		origEnd := lastCharOrigIdx + 1

		if isDebug {
			m.log.Debug("applying stars",
				"pattern", string(match.Word),
				"orig_start", origStart,
				"orig_end", origEnd)
		}

		for i := origStart; i < origEnd; i++ {
			origRunes[i] = m.censoredChar
		}
	}

	result := string(origRunes)
	if isDebug {
		m.log.Debug("censor finished", "final", result)
	}

	return result, foundWords
}

// normalize transforms the input string into a searchable format and tracks original rune positions.
func (m *Moderator) normalize(input string) TextMapping {
	origRunes := []rune(input)
	norm := make([]rune, 0, len(origRunes))
	origIdx := make([]int, 0, len(origRunes))

	for i, r := range origRunes {
		// On vérifie le caractère ORIGINAL 'r'
		if isNoise(r) {
			continue
		}
		clean := simplifyRune(r)
		norm = append(norm, unicode.ToLower(clean))
		origIdx = append(origIdx, i)
	}
	return TextMapping{Normalized: norm, OrigIdx: origIdx}
}

// normalizeRunes applies simplification and noise removal to a slice of runes.
func normalizeRunes(input []rune) []rune {
	out := make([]rune, 0, len(input))
	for _, r := range input {
		clean := simplifyRune(r)
		if isNoise(clean) { // clean ici sera 'i' pour '!', donc isNoise sera faux
			continue
		}
		out = append(out, unicode.ToLower(clean))
	}
	return out
}

// simplifyRune maps common Leet speak characters back to their standard alphabet counterparts.
func simplifyRune(r rune) rune {
	switch r {
	case '4', '@':
		return 'a'
	case '3', '€':
		return 'e'
	case '1', '!', '|':
		return 'i'
	case '0':
		return 'o'
	case '5', '$':
		return 's'
	default:
		return r
	}
}

// isNoise identifies characters that should be ignored during the pattern matching phase.
func isNoise(r rune) bool {
	// Si c'est un caractère que tu simplifies (Leet Speak), on ne le considère PAS comme du bruit
	switch r {
	case '4', '@', '3', '€', '1', '!', '|', '0', '5', '$':
		return false
	}
	return unicode.IsPunct(r) || unicode.IsSpace(r) || unicode.IsSymbol(r)
}
