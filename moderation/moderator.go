package moderation

import (
	"fmt"
	goahocorasick "github.com/anknown/ahocorasick"
	"unicode"
)

type Moderator struct {
	matcher      *goahocorasick.Machine
	censoredChar rune
}

type TextMapping struct {
	Normalized []rune
	OrigIdx    []int
}

// NewModerator initializes the Aho-Corasick automaton with a normalized version of the provided censored words list.
func NewModerator(censoredWords []string, censoredChar rune) (Moderator, error) {
	patterns := make([][]rune, 0, len(censoredWords)) // Change la taille initiale à 0
	for _, word := range censoredWords {
		normalized := normalizeRunes([]rune(word))
		if len(normalized) > 0 { // <--- Sécurité cruciale
			patterns = append(patterns, normalized)
		}
	}

	if len(patterns) == 0 {
		return Moderator{}, fmt.Errorf("no valid patterns after normalization")
	}

	m := new(goahocorasick.Machine)
	if err := m.Build(patterns); err != nil {
		return Moderator{}, err
	}
	return Moderator{matcher: m, censoredChar: censoredChar}, nil
}

// Censor identifies forbidden patterns and replaces the original characters with stars while preserving spacing.
func (m *Moderator) Censor(original string) string {
	mapping := m.normalize(original)
	if len(mapping.Normalized) == 0 {
		return original
	}

	origRunes := []rune(original)
	spans := m.matcher.MultiPatternSearch(mapping.Normalized, false)
	if len(spans) == 0 {
		return original
	}

	for _, span := range spans {
		normStart := span.Pos
		normEnd := normStart + len(span.Word)

		if normStart < 0 || normEnd > len(mapping.OrigIdx) {
			continue
		}

		origStart := mapping.OrigIdx[normStart]
		lastCharOrigIdx := mapping.OrigIdx[normEnd-1]
		origEnd := lastCharOrigIdx + 1

		for i := origStart; i < origEnd; i++ {
			origRunes[i] = m.censoredChar
		}
	}

	return string(origRunes)
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
