package query

import (
	"strings"
	"unicode"
)

// A place is read in two ways:
//   - a known name (country or large city) after a preposition: "au mexique",
//     "de lisbonne", even without a capital letter, as dictation often gives;
//   - any capitalized name after "à", "au", "aux" or "en": "à Trouville".
//
// The lists below are only what makes the first way work without capitals.
// They do not limit the places that can be searched, and WithPlaces adds the
// user's own.

const knownCountries = `afghanistan|afrique du sud|albanie|algerie|allemagne|andorre|angleterre|angola|arabie saoudite|argentine|
armenie|australie|autriche|azerbaidjan|bahamas|bangladesh|belgique|benin|bolivie|bosnie|botswana|bresil|bulgarie|burkina faso|
cambodge|cameroun|canada|cap vert|chili|chine|chypre|colombie|congo|coree du sud|coree du nord|costa rica|cote d ivoire|croatie|
cuba|danemark|ecosse|egypte|emirats arabes unis|equateur|espagne|estonie|etats unis|ethiopie|finlande|france|gabon|ghana|
grece|guatemala|guinee|haiti|honduras|hongrie|inde|indonesie|irak|iran|irlande|islande|israel|italie|jamaique|japon|jordanie|
kazakhstan|kenya|kosovo|laos|lettonie|liban|libye|lituanie|luxembourg|macedoine|madagascar|malaisie|mali|malte|maroc|
mauritanie|mexique|moldavie|monaco|mongolie|montenegro|mozambique|namibie|nepal|nicaragua|niger|nigeria|norvege|nouvelle zelande|
ouzbekistan|pakistan|panama|paraguay|pays bas|pays de galles|perou|philippines|pologne|polynesie|portugal|qatar|
republique dominicaine|republique tcheque|roumanie|royaume uni|russie|rwanda|salvador|senegal|serbie|seychelles|singapour|
slovaquie|slovenie|sri lanka|suede|suisse|syrie|taiwan|tanzanie|tchad|thailande|togo|tunisie|turquie|ukraine|uruguay|venezuela|
vietnam|zambie|zimbabwe|corse|bretagne|normandie|provence|alsace|guadeloupe|martinique|guyane|sicile|sardaigne|crete|
californie|floride|quebec|ecosse|catalogne|andalousie|toscane`

const knownCities = `paris|marseille|lyon|toulouse|nantes|montpellier|strasbourg|bordeaux|lille|rennes|reims|toulon|
grenoble|dijon|angers|nimes|clermont ferrand|le havre|le mans|aix en provence|brest|amiens|limoges|annecy|perpignan|
metz|besancon|orleans|rouen|caen|avignon|la rochelle|biarritz|cannes|saint tropez|saint malo|chamonix|ajaccio|bastia|
londres|berlin|madrid|rome|lisbonne|barcelone|amsterdam|bruxelles|prague|budapest|varsovie|athenes|dublin|copenhague|
stockholm|oslo|helsinki|zurich|geneve|lausanne|milan|venise|naples|seville|porto|munich|hambourg|francfort|
edimbourg|istanbul|moscou|new york|los angeles|san francisco|chicago|miami|las vegas|boston|washington|montreal|toronto|
vancouver|cancun|la havane|sao paulo|buenos aires|lima|bogota|tokyo|kyoto|osaka|pekin|shanghai|hong kong|
seoul|bangkok|hanoi|singapour|bali|delhi|bombay|dubai|doha|jerusalem|tel aviv|le caire|marrakech|casablanca|rabat|tunis|alger|
dakar|abidjan|nairobi|le cap|johannesburg|melbourne|auckland`

// knownPlaces maps the first word of a known name to the full names starting
// with it, each as a list of words.
var knownPlaces = buildPlaces(knownCountries, knownCities)

func buildPlaces(lists ...string) map[string][][]string {
	out := make(map[string][][]string)
	for _, list := range lists {
		for _, name := range strings.Split(list, "|") {
			addPlace(out, name)
		}
	}
	return out
}

func addPlace(places map[string][][]string, name string) {
	words := strings.Fields(normalize(name))
	if len(words) > 0 {
		places[words[0]] = append(places[words[0]], words)
	}
}

var (
	// locative prepositions introduce a place by themselves.
	locative = wordSet("a au aux en vers")
	// loose prepositions only do with a known name: "les photos de Lisbonne".
	loosePrep = wordSet("de d du des")
)

// matchKnownPlace returns how many words at position i form a known name.
func (a *analysis) matchKnownPlace(places map[string][][]string, i int) int {
	best := 0
	for _, words := range places[a.toks[i].norm] {
		if i+len(words) > len(a.toks) || len(words) <= best {
			continue
		}
		matched := true
		for k, w := range words {
			if a.toks[i+k].used || a.toks[i+k].norm != w {
				matched = false
				break
			}
		}
		if matched {
			best = len(words)
		}
	}
	return best
}

// capitalized reports whether the word at position i starts with a capital
// letter and is not written all in capitals, like an acronym.
func (a *analysis) capitalized(i int) bool {
	word := []rune(a.sentence[a.toks[i].start:a.toks[i].end])
	if len(word) < 2 || !unicode.IsUpper(word[0]) {
		return false
	}
	for _, r := range word[1:] {
		if unicode.IsLower(r) {
			return true
		}
	}
	return false
}

// extractPlace reads where the files were taken.
func (a *analysis) extractPlace(places map[string][][]string) {
	for i := 1; i < len(a.toks); i++ {
		if a.plan.Place != nil {
			return
		}
		prep := a.toks[i-1]
		if a.toks[i].used || prep.used {
			continue
		}
		isLocative, isLoose := in(locative, prep.norm), in(loosePrep, prep.norm)
		if !isLocative && !isLoose {
			continue
		}
		// Without a capital letter, a known name only counts after "à", "au",
		// "en": "une photo de nice" is not the city.
		length := 0
		if isLocative || a.capitalized(i) {
			length = a.matchKnownPlace(places, i)
		}
		if length == 0 && isLocative && a.capitalized(i) {
			length = 1
			for i+length < len(a.toks) && !a.toks[i+length].used && a.capitalized(i+length) {
				length++
			}
		}
		if length == 0 {
			continue
		}
		start, end := a.toks[i].start, a.toks[i+length-1].end
		a.plan.Place = &Place{
			Name:   a.sentence[start:end],
			Origin: Origin{Start: prep.start, End: end, Text: a.sentence[prep.start:end], Rule: "place"},
		}
		use(a.toks, i-1, length+1)
	}
}

// WithPlaces adds names of places the user cares about, so that they are
// recognized without a capital letter: a village, a neighbourhood, a house.
func WithPlaces(names ...string) Option {
	return func(p *Parser) {
		places := make(map[string][][]string, len(p.places)+len(names))
		for first, list := range p.places {
			places[first] = list
		}
		for _, name := range names {
			addPlace(places, name)
		}
		p.places = places
	}
}
