package query

import (
	"strconv"
	"strings"
	"time"
	"unicode"
)

// token is one word of the sentence, lowercased and without accents, with its
// place in the original sentence.
type token struct {
	norm       string
	start, end int
	// used is set once a rule has taken the word, so that it is not also read
	// as part of the free description.
	used bool
	// skip marks a word that carries no meaning where it stands ("prises" in
	// "les photos prises à la plage"). Unlike a used word, it does not cut the
	// description in two: the words around it stay together.
	skip bool
	// out marks a word of what the user does not want: it must not be read as
	// something asked for ("sauf les selfies" does not ask for portraits).
	out bool
}

// tokenize splits a sentence into words. Apostrophes, hyphens and punctuation
// separate words: "l'an dernier" gives l, an, dernier; "lave-vaisselle" gives
// lave, vaisselle.
func tokenize(s string) []token {
	var (
		toks  []token
		b     strings.Builder
		start = -1
	)
	flush := func(end int) {
		if start >= 0 {
			toks = append(toks, token{norm: b.String(), start: start, end: end})
			b.Reset()
			start = -1
		}
	}
	for i, r := range s {
		switch {
		case unicode.IsLetter(r) || unicode.IsDigit(r):
			if start < 0 {
				start = i
			}
			b.WriteString(fold(r))
		case unicode.Is(unicode.Mn, r):
			// A combining accent belongs to the letter before it.
		default:
			flush(i)
		}
	}
	flush(len(s))
	return toks
}

// fold lowercases a letter and removes its accent.
func fold(r rune) string {
	r = unicode.ToLower(r)
	switch r {
	case 'à', 'â', 'ä', 'á', 'ã':
		return "a"
	case 'ç':
		return "c"
	case 'é', 'è', 'ê', 'ë':
		return "e"
	case 'î', 'ï', 'í':
		return "i"
	case 'ô', 'ö', 'ó':
		return "o"
	case 'ù', 'û', 'ü', 'ú':
		return "u"
	case 'ÿ':
		return "y"
	case 'ñ':
		return "n"
	case 'œ':
		return "oe"
	case 'æ':
		return "ae"
	}
	return string(r)
}

// normalize returns the words of s, folded, separated by one space.
func normalize(s string) string {
	toks := tokenize(s)
	parts := make([]string, len(toks))
	for i, t := range toks {
		parts[i] = t.norm
	}
	return strings.Join(parts, " ")
}

// unit is a unit of time used in relative dates.
type unit int

const (
	unitNone unit = iota
	unitDay
	unitWeek
	unitMonth
	unitYear
)

var units = map[string]unit{
	"jour": unitDay, "jours": unitDay, "journee": unitDay, "journees": unitDay,
	"semaine": unitWeek, "semaines": unitWeek,
	"mois": unitMonth,
	"an":   unitYear, "ans": unitYear, "annee": unitYear, "annees": unitYear,
}

var months = map[string]time.Month{
	"janvier": time.January, "janv": time.January,
	"fevrier": time.February, "fev": time.February, "fevr": time.February,
	"mars":  time.March,
	"avril": time.April, "avr": time.April,
	"mai":     time.May,
	"juin":    time.June,
	"juillet": time.July, "juil": time.July,
	"aout":      time.August,
	"septembre": time.September, "sept": time.September,
	"octobre": time.October, "oct": time.October,
	"novembre": time.November, "nov": time.November,
	"decembre": time.December, "dec": time.December,
}

var numberWords = map[string]int{
	"zero": 0, "un": 1, "une": 1, "deux": 2, "trois": 3, "quatre": 4, "cinq": 5,
	"six": 6, "sept": 7, "huit": 8, "neuf": 9, "dix": 10, "onze": 11, "douze": 12,
	"treize": 13, "quatorze": 14, "quinze": 15, "seize": 16, "vingt": 20,
	"trente": 30, "quarante": 40, "cinquante": 50, "soixante": 60, "cent": 100,
	"quelques": 3, "plusieurs": 3,
}

// roundNumbers are spoken approximations: "une quinzaine de jours".
var roundNumbers = map[string]int{
	"dizaine": 10, "douzaine": 12, "quinzaine": 15, "vingtaine": 20, "trentaine": 30,
	"quarantaine": 40, "cinquantaine": 50, "centaine": 100,
}

// parseNumber reads a number at position i, written with digits or in words.
// It returns the value and how many words it spans.
func parseNumber(toks []token, i int) (value, length int, ok bool) {
	if i >= len(toks) || toks[i].used {
		return 0, 0, false
	}
	word := toks[i].norm
	if isDigits(word) && len(word) <= 3 {
		n, err := strconv.Atoi(word)
		if err != nil {
			return 0, 0, false
		}
		return n, 1, true
	}
	// "une dizaine de", "une quinzaine d'"
	if (word == "un" || word == "une") && i+1 < len(toks) && !toks[i+1].used {
		if n, found := roundNumbers[toks[i+1].norm]; found {
			length = 2
			if i+2 < len(toks) && !toks[i+2].used && (toks[i+2].norm == "de" || toks[i+2].norm == "d") {
				length = 3
			}
			return n, length, true
		}
	}
	if n, found := numberWords[word]; found {
		return n, 1, true
	}
	return 0, 0, false
}

func isDigits(s string) bool {
	if s == "" {
		return false
	}
	for _, r := range s {
		if r < '0' || r > '9' {
			return false
		}
	}
	return true
}

// parseYear reads a four-digit year.
func parseYear(word string) (int, bool) {
	if len(word) != 4 || !isDigits(word) {
		return 0, false
	}
	year, _ := strconv.Atoi(word)
	if year < 1990 || year > 2099 {
		return 0, false
	}
	return year, true
}

// captures holds what a pattern read in the sentence.
type captures struct {
	n     int
	hasN  bool
	unit  unit
	month time.Month
	year  int
	// day and day2 are days of the month, in the order they were read.
	day, day2 int
}

// element is one position of a pattern.
type element struct {
	// kind is "" for a list of accepted words, or one of n, unit, month, year.
	kind     string
	words    map[string]struct{}
	optional bool
}

// pattern is a sequence of words to recognize, written as a compact string:
//
//	"il y a <n> <unit>"         a number then a unit of time
//	"ces|les <n> derniers mois" alternatives are separated by |
//	"ce mois ci?"               a trailing ? makes a word optional
type pattern []element

func compile(spec string) pattern {
	fields := strings.Fields(spec)
	p := make(pattern, 0, len(fields))
	for _, field := range fields {
		var e element
		if strings.HasSuffix(field, "?") {
			e.optional = true
			field = strings.TrimSuffix(field, "?")
		}
		if strings.HasPrefix(field, "<") && strings.HasSuffix(field, ">") {
			e.kind = field[1 : len(field)-1]
		} else {
			e.words = make(map[string]struct{})
			for _, word := range strings.Split(field, "|") {
				e.words[word] = struct{}{}
			}
		}
		p = append(p, e)
	}
	return p
}

func compileAll(specs ...string) []pattern {
	out := make([]pattern, len(specs))
	for i, spec := range specs {
		out[i] = compile(spec)
	}
	return out
}

// match tries the pattern at position i and returns how many words it covers.
// Words already taken by another rule never match.
func (p pattern) match(toks []token, i int) (int, captures, bool) {
	var c captures
	length, ok := p.matchFrom(0, toks, i, &c)
	return length, c, ok
}

func (p pattern) matchFrom(ei int, toks []token, ti int, c *captures) (int, bool) {
	if ei == len(p) {
		return 0, true
	}
	e := p[ei]
	if ti < len(toks) && !toks[ti].used {
		saved := *c
		if consumed, ok := e.consume(toks, ti, c); ok {
			if rest, ok := p.matchFrom(ei+1, toks, ti+consumed, c); ok {
				return consumed + rest, true
			}
		}
		*c = saved
	}
	if e.optional {
		return p.matchFrom(ei+1, toks, ti, c)
	}
	return 0, false
}

func (e element) consume(toks []token, i int, c *captures) (int, bool) {
	word := toks[i].norm
	switch e.kind {
	case "":
		_, ok := e.words[word]
		return 1, ok
	case "n":
		n, length, ok := parseNumber(toks, i)
		if ok {
			c.n, c.hasN = n, true
		}
		return length, ok
	case "unit":
		u, ok := units[word]
		if ok {
			c.unit = u
		}
		return 1, ok
	case "month":
		m, ok := months[word]
		if ok {
			c.month = m
		}
		return 1, ok
	case "year":
		y, ok := parseYear(word)
		if ok {
			c.year = y
		}
		return 1, ok
	case "day":
		d, ok := parseDay(word)
		if ok {
			if c.day == 0 {
				c.day = d
			} else {
				c.day2 = d
			}
		}
		return 1, ok
	}
	return 0, false
}

// parseDay reads a day of the month: "18", "1er", "premier".
func parseDay(word string) (int, bool) {
	if word == "1er" || word == "premier" {
		return 1, true
	}
	if !isDigits(word) || len(word) > 2 {
		return 0, false
	}
	day, _ := strconv.Atoi(word)
	return day, day >= 1 && day <= 31
}

// findFirst returns the first pattern matching at position i, preferring the
// longest one.
func findFirst(patterns []pattern, toks []token, i int) (length int, c captures, index int, ok bool) {
	index = -1
	for pi, p := range patterns {
		if l, pc, matched := p.match(toks, i); matched && l > length {
			length, c, index, ok = l, pc, pi, true
		}
	}
	return length, c, index, ok
}

// use marks words as taken by a rule.
func use(toks []token, i, length int) {
	for k := i; k < i+length && k < len(toks); k++ {
		toks[k].used = true
	}
}

// originOf builds the origin of a criterion read over words i to i+length-1.
func originOf(sentence string, toks []token, i, length int, rule string) Origin {
	start, end := toks[i].start, toks[i+length-1].end
	return Origin{Start: start, End: end, Text: sentence[start:end], Rule: rule}
}

// Fold returns the words of s in lower case and without accents, separated by
// one space: the form under which names are compared ("États-Unis" and
// "etats unis" are the same place).
func Fold(s string) string {
	return normalize(s)
}
