package query

import (
	"fmt"
	"time"
)

// Relative dates are computed by rules, never by a model: "a month ago" must
// give the same period every time, and the clock is passed in so that the
// rules can be tested.
//
// Two kinds of periods come out:
//   - exact ones ("yesterday", "in March", "last year"), taken as said;
//   - rough ones ("a month ago", "these last 6 months"), widened by a margin,
//     because nobody remembers a date to the day and a missed file is worse
//     than an extra one.

// earliest is the oldest date a search goes back to.
var earliest = time.Date(1990, time.January, 1, 0, 0, 0, 0, time.UTC)

func startOfDay(t time.Time) time.Time {
	y, m, d := t.Date()
	return time.Date(y, m, d, 0, 0, 0, 0, t.Location())
}

func tomorrow(now time.Time) time.Time {
	return startOfDay(now).AddDate(0, 0, 1)
}

func shift(t time.Time, n int, u unit) time.Time {
	switch u {
	case unitDay:
		return t.AddDate(0, 0, n)
	case unitWeek:
		return t.AddDate(0, 0, 7*n)
	case unitMonth:
		return t.AddDate(0, n, 0)
	case unitYear:
		return t.AddDate(n, 0, 0)
	}
	return t
}

func spanDays(n int, u unit) int {
	switch u {
	case unitWeek:
		return 7 * n
	case unitMonth:
		return 30 * n
	case unitYear:
		return 365 * n
	}
	return n
}

// period is a date range before it gets its label and origin.
type period struct {
	from, to time.Time
	approx   bool
}

// around is "N units ago": a window around that day, wider on the old side
// because people underestimate how long ago something happened. A month ago
// covers 20 to 45 days back.
func around(now time.Time, n int, u unit) period {
	center := startOfDay(shift(now, -n, u))
	days := spanDays(n, u)
	before, after := days/2, days/3
	if days >= 3 && after == 0 {
		after = 1
	}
	return period{from: center.AddDate(0, 0, -before), to: center.AddDate(0, 0, after+1), approx: true}
}

// last is "these last N units": from then until now, with a 10 % margin.
func last(now time.Time, n int, u unit) period {
	margin := min(max(spanDays(n, u)/10, 1), 31)
	from := startOfDay(shift(now, -n, u)).AddDate(0, 0, -margin)
	return period{from: from, to: tomorrow(now), approx: true}
}

func singleDay(now time.Time, offset int) period {
	from := startOfDay(now).AddDate(0, 0, offset)
	return period{from: from, to: from.AddDate(0, 0, 1)}
}

// weekStart returns the Monday of the week of t.
func weekStart(t time.Time) time.Time {
	offset := (int(t.Weekday()) + 6) % 7
	return startOfDay(t).AddDate(0, 0, -offset)
}

func monthStart(year int, month time.Month, loc *time.Location) time.Time {
	return time.Date(year, month, 1, 0, 0, 0, 0, loc)
}

// wholeMonth is a named month. Without a year, it is the most recent one that
// has started: "in March" said in November means last March, said in February
// it means March of the year before.
func wholeMonth(now time.Time, month time.Month, year int) period {
	if year == 0 {
		year = now.Year()
		if month > now.Month() {
			year--
		}
	}
	from := monthStart(year, month, now.Location())
	return period{from: from, to: from.AddDate(0, 1, 0)}
}

// exactDay is one named day. Without a year, it is the most recent such day
// that is not in the future. A day that does not exist (31 février) gives an
// empty period, which is ignored.
func exactDay(now time.Time, day int, month time.Month, year int) period {
	if year == 0 {
		year = now.Year()
		if time.Date(year, month, day, 0, 0, 0, 0, now.Location()).After(now) {
			year--
		}
	}
	from := time.Date(year, month, day, 0, 0, 0, 0, now.Location())
	if from.Day() != day || from.Month() != month {
		return period{}
	}
	return period{from: from, to: from.AddDate(0, 0, 1)}
}

func wholeYear(now time.Time, year int) period {
	from := monthStart(year, time.January, now.Location())
	return period{from: from, to: from.AddDate(1, 0, 0)}
}

// season is a season of a given year, taken wide. Winter of year Y starts in
// December of Y-1.
func season(name string, year int, loc *time.Location) period {
	switch name {
	case "printemps":
		return period{from: monthStart(year, time.March, loc), to: monthStart(year, time.July, loc), approx: true}
	case "ete":
		return period{from: monthStart(year, time.June, loc), to: monthStart(year, time.October, loc), approx: true}
	case "automne":
		return period{from: monthStart(year, time.September, loc), to: monthStart(year+1, time.January, loc), approx: true}
	default: // hiver
		return period{from: monthStart(year-1, time.December, loc), to: monthStart(year, time.April, loc), approx: true}
	}
}

// recentSeason finds the most recent season of that name that has started. With
// previous set ("last summer"), a season still running is skipped.
func recentSeason(now time.Time, name string, previous bool) period {
	for year := now.Year() + 1; ; year-- {
		p := season(name, year, now.Location())
		if p.from.After(now) {
			continue
		}
		if previous && now.Before(p.to) {
			continue
		}
		return p
	}
}

func christmas(now time.Time, year int) period {
	if year == 0 {
		year = now.Year()
		if now.Before(time.Date(year, time.December, 20, 0, 0, 0, 0, now.Location())) {
			year--
		}
	}
	from := time.Date(year, time.December, 20, 0, 0, 0, 0, now.Location())
	return period{from: from, to: from.AddDate(0, 0, 9)}
}

// dateRule is a wording and the period it stands for.
type dateRule struct {
	name    string
	pattern pattern
	build   func(c captures, now time.Time) period
}

func rule(name, spec string, build func(c captures, now time.Time) period) dateRule {
	return dateRule{name: name, pattern: compile(spec), build: build}
}

// fewOf is what "these last days / weeks / months" means without a number.
func fewOf(u unit) int {
	if u == unitDay {
		return 7
	}
	return 3
}

var dateRules = []dateRule{
	rule("day-before-yesterday", "avant hier", func(_ captures, now time.Time) period { return singleDay(now, -2) }),
	rule("ago", "il? y a <n> <unit>", func(c captures, now time.Time) period { return around(now, c.n, c.unit) }),
	rule("ago", "voila <n> <unit>", func(c captures, now time.Time) period { return around(now, c.n, c.unit) }),
	rule("last-n", "ces|les|des|ses|mes? <n> derniers|dernieres|dernier|derniere <unit>",
		func(c captures, now time.Time) period { return last(now, c.n, c.unit) }),
	rule("last-few", "ces|les|des|ses|mes? derniers|dernieres <unit>",
		func(c captures, now time.Time) period { return last(now, fewOf(c.unit), c.unit) }),
	rule("less-than", "de? moins de|d <n> <unit>", func(c captures, now time.Time) period { return last(now, c.n, c.unit) }),
	rule("more-than", "de? plus de|d <n> <unit>", func(c captures, now time.Time) period {
		return period{from: earliest.In(now.Location()), to: startOfDay(shift(now, -c.n, c.unit)).AddDate(0, 0, 1), approx: true}
	}),
	rule("since-n", "depuis <n> <unit>", func(c captures, now time.Time) period { return last(now, c.n, c.unit) }),
	rule("since-yesterday", "depuis hier", func(_ captures, now time.Time) period {
		return period{from: startOfDay(now).AddDate(0, 0, -1), to: tomorrow(now)}
	}),
	rule("last-week", "la? semaine derniere|passee|precedente", func(_ captures, now time.Time) period {
		start := weekStart(now)
		return period{from: start.AddDate(0, 0, -7), to: start}
	}),
	rule("last-month", "le? mois dernier|passe|precedent", func(_ captures, now time.Time) period {
		start := monthStart(now.Year(), now.Month(), now.Location())
		return period{from: start.AddDate(0, -1, 0), to: start}
	}),
	rule("last-year", "an|annee dernier|derniere|passe|passee|precedent|precedente", func(_ captures, now time.Time) period {
		return wholeYear(now, now.Year()-1)
	}),
	rule("last-weekend", "week end dernier|passe|precedent", func(_ captures, now time.Time) period {
		start := weekStart(now)
		return period{from: start.AddDate(0, 0, -2), to: start}
	}),
	rule("this-weekend", "ce week end", func(_ captures, now time.Time) period {
		start := weekStart(now)
		if day := now.Weekday(); day == time.Saturday || day == time.Sunday {
			return period{from: start.AddDate(0, 0, 5), to: tomorrow(now)}
		}
		return period{from: start.AddDate(0, 0, -2), to: start}
	}),
	rule("this-week", "cette semaine ci?", func(_ captures, now time.Time) period {
		return period{from: weekStart(now), to: tomorrow(now)}
	}),
	rule("this-month", "ce mois ci?", func(_ captures, now time.Time) period {
		return period{from: monthStart(now.Year(), now.Month(), now.Location()), to: tomorrow(now)}
	}),
	rule("this-year", "cette annee ci?", func(_ captures, now time.Time) period {
		return period{from: monthStart(now.Year(), time.January, now.Location()), to: tomorrow(now)}
	}),
	rule("today", "aujourd hui", func(_ captures, now time.Time) period { return singleDay(now, 0) }),
	rule("today", "ce matin|midi|soir", func(_ captures, now time.Time) period { return singleDay(now, 0) }),
	rule("today", "cet apres midi", func(_ captures, now time.Time) period { return singleDay(now, 0) }),
	rule("yesterday", "hier matin|midi|soir?", func(_ captures, now time.Time) period { return singleDay(now, -1) }),
	rule("christmas", "noel dernier? <year>?", func(c captures, now time.Time) period { return christmas(now, c.year) }),
	rule("day-range", "entre|du le? <day> et|au le? <day> <month> <year>?", func(c captures, now time.Time) period {
		first := exactDay(now, c.day, c.month, c.year)
		if first.from.IsZero() || c.day2 < c.day {
			return period{}
		}
		return period{from: first.from, to: first.from.AddDate(0, 0, c.day2-c.day+1)}
	}),
	rule("day", "le? <day> <month> <year>?", func(c captures, now time.Time) period {
		return exactDay(now, c.day, c.month, c.year)
	}),
	rule("month", "en|de|du|d|debut|fin|courant|mi <month> <year>?",
		func(c captures, now time.Time) period { return wholeMonth(now, c.month, c.year) }),
	rule("month", "mois de|d <month> <year>?",
		func(c captures, now time.Time) period { return wholeMonth(now, c.month, c.year) }),
	rule("month", "<month> <year>", func(c captures, now time.Time) period { return wholeMonth(now, c.month, c.year) }),
	rule("month", "<month> dernier|passe", func(c captures, now time.Time) period {
		year := now.Year()
		if c.month >= now.Month() {
			year--
		}
		return wholeMonth(now, c.month, year)
	}),
	rule("year", "en|de|d|annee|an? <year>", func(c captures, now time.Time) period { return wholeYear(now, c.year) }),
}

var seasonNames = map[string]struct{}{"printemps": {}, "ete": {}, "automne": {}, "hiver": {}}

// matchSeason reads "cet été", "l'été dernier", "été 2024". The season name
// alone is not enough: "été" is also a form of the verb "être".
func matchSeason(toks []token, i int, now time.Time) (period, int, bool) {
	start := i
	qualified := false
	if w := toks[i].norm; w == "cet" || w == "ce" || w == "l" || w == "dernier" {
		qualified = true
		i++
	}
	if i >= len(toks) || toks[i].used {
		return period{}, 0, false
	}
	name := toks[i].norm
	if _, ok := seasonNames[name]; !ok {
		return period{}, 0, false
	}
	i++
	previous := start < len(toks) && toks[start].norm == "dernier"
	if i < len(toks) && !toks[i].used {
		if year, ok := parseYear(toks[i].norm); ok {
			return season(name, year, now.Location()), i + 1 - start, true
		}
		if w := toks[i].norm; w == "dernier" || w == "passe" || w == "precedent" {
			previous, qualified = true, true
			i++
		}
	}
	if !qualified {
		return period{}, 0, false
	}
	return recentSeason(now, name, previous), i - start, true
}

var weekdays = map[string]time.Weekday{
	"lundi": time.Monday, "mardi": time.Tuesday, "mercredi": time.Wednesday, "jeudi": time.Thursday,
	"vendredi": time.Friday, "samedi": time.Saturday, "dimanche": time.Sunday,
}

// matchWeekday reads "samedi dernier" or "mardi": the most recent such day
// before today.
func matchWeekday(toks []token, i int, now time.Time) (period, int, bool) {
	weekday, ok := weekdays[toks[i].norm]
	if !ok {
		return period{}, 0, false
	}
	back := (int(now.Weekday()) - int(weekday) + 7) % 7
	if back == 0 {
		back = 7
	}
	length := 1
	if i+1 < len(toks) && !toks[i+1].used {
		if w := toks[i+1].norm; w == "dernier" || w == "passe" {
			length = 2
		}
	}
	return singleDay(now, -back), length, true
}

// atomFillers may stand between "avant" / "depuis" / "entre" and the date.
var atomFillers = map[string]struct{}{"le": {}, "la": {}, "l": {}, "mois": {}, "de": {}, "d": {}, "annee": {}, "debut": {}, "fin": {}}

// matchAtom reads a month (with or without a year) or a year.
func matchAtom(toks []token, i int, now time.Time) (period, int, bool) {
	start := i
	for skipped := 0; i < len(toks) && !toks[i].used && skipped < 3; skipped++ {
		if _, ok := atomFillers[toks[i].norm]; !ok {
			break
		}
		i++
	}
	if i >= len(toks) || toks[i].used {
		return period{}, 0, false
	}
	if month, ok := months[toks[i].norm]; ok {
		year := 0
		i++
		if i < len(toks) && !toks[i].used {
			if y, ok := parseYear(toks[i].norm); ok {
				year = y
				i++
			}
		}
		return wholeMonth(now, month, year), i - start, true
	}
	if year, ok := parseYear(toks[i].norm); ok {
		return wholeYear(now, year), i + 1 - start, true
	}
	return period{}, 0, false
}

// matchBounded reads "avant mars", "après 2023", "depuis janvier" and
// "entre janvier et mars".
func matchBounded(toks []token, i int, now time.Time) (period, int, string, bool) {
	word := toks[i].norm
	switch word {
	case "avant", "apres", "depuis":
		atom, length, ok := matchAtom(toks, i+1, now)
		if !ok {
			return period{}, 0, "", false
		}
		switch word {
		case "avant":
			return period{from: earliest.In(now.Location()), to: atom.from}, length + 1, "before", true
		case "apres":
			return period{from: atom.to, to: tomorrow(now)}, length + 1, "after", true
		default:
			return period{from: atom.from, to: tomorrow(now)}, length + 1, "since", true
		}
	case "entre":
		first, firstLen, ok := matchAtom(toks, i+1, now)
		if !ok {
			return period{}, 0, "", false
		}
		j := i + 1 + firstLen
		if j >= len(toks) || toks[j].used || toks[j].norm != "et" {
			return period{}, 0, "", false
		}
		second, secondLen, ok := matchAtom(toks, j+1, now)
		if !ok {
			return period{}, 0, "", false
		}
		return period{from: first.from, to: second.to}, 2 + firstLen + secondLen, "between", true
	}
	return period{}, 0, "", false
}

// extractDates finds every period named in the sentence and takes its words.
func extractDates(sentence string, toks []token, now time.Time) []DateRange {
	var found []DateRange
	add := func(p period, i, length int, name string) {
		if end := tomorrow(now); p.to.After(end) {
			p.to = end
		}
		if !p.from.Before(p.to) {
			// A period entirely in the future cannot match any file: ignore it
			// instead of returning nothing.
			return
		}
		found = append(found, DateRange{
			From: p.from, To: p.to, Approx: p.approx,
			Label:  formatRange(p.from, p.to, now),
			Origin: originOf(sentence, toks, i, length, "date:"+name),
		})
		use(toks, i, length)
	}

	for i := 0; i < len(toks); i++ {
		if toks[i].used {
			continue
		}
		if p, length, name, ok := matchBounded(toks, i, now); ok {
			add(p, i, length, name)
			continue
		}
		if p, length, ok := matchWeekday(toks, i, now); ok {
			add(p, i, length, "weekday")
			continue
		}
		if p, length, ok := matchSeason(toks, i, now); ok {
			add(p, i, length, "season")
			continue
		}
		best, bestLen := -1, 0
		var bestCaptures captures
		for ri, r := range dateRules {
			if length, c, ok := r.pattern.match(toks, i); ok && length > bestLen {
				best, bestLen, bestCaptures = ri, length, c
			}
		}
		if best >= 0 {
			r := dateRules[best]
			add(r.build(bestCaptures, now), i, bestLen, r.name)
		}
	}
	return found
}

var monthNames = [...]string{"janvier", "février", "mars", "avril", "mai", "juin",
	"juillet", "août", "septembre", "octobre", "novembre", "décembre"}

func formatDay(t time.Time, withYear bool) string {
	day := fmt.Sprintf("%d", t.Day())
	if t.Day() == 1 {
		day = "1er"
	}
	if withYear {
		return fmt.Sprintf("%s %s %d", day, monthNames[t.Month()-1], t.Year())
	}
	return fmt.Sprintf("%s %s", day, monthNames[t.Month()-1])
}

// formatRange writes a period the way it is shown to the user.
func formatRange(from, to, now time.Time) string {
	lastDay := to.AddDate(0, 0, -1)
	switch {
	case from.AddDate(0, 0, 1).Equal(to):
		return "le " + formatDay(from, true)
	case from.Day() == 1 && from.Month() == time.January && from.AddDate(1, 0, 0).Equal(to):
		return fmt.Sprintf("%d", from.Year())
	case from.Day() == 1 && from.AddDate(0, 1, 0).Equal(to):
		return fmt.Sprintf("%s %d", monthNames[from.Month()-1], from.Year())
	case !from.After(earliest.In(from.Location())):
		return "avant le " + formatDay(to, true)
	case !to.Before(tomorrow(now)):
		return "depuis le " + formatDay(from, true)
	case from.Year() == lastDay.Year():
		return fmt.Sprintf("du %s au %s", formatDay(from, false), formatDay(lastDay, true))
	default:
		return fmt.Sprintf("du %s au %s", formatDay(from, true), formatDay(lastDay, true))
	}
}
