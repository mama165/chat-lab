package query

import (
	"slices"
	"strings"
	"time"
)

// Parser translates a sentence into a search plan.
type Parser struct {
	lexicon *Lexicon
}

// Option configures a Parser.
type Option func(*Parser)

// WithLexicon replaces the default lexicon.
func WithLexicon(l *Lexicon) Option {
	return func(p *Parser) { p.lexicon = l }
}

// NewParser returns a parser using the default lexicon.
func NewParser(opts ...Option) *Parser {
	p := &Parser{lexicon: DefaultLexicon()}
	for _, opt := range opts {
		opt(p)
	}
	return p
}

// Result is what one sentence asks for.
type Result struct {
	Plan Plan `json:"plan"`
	// Then is a second, wider search asked in the same sentence: "the QR code
	// I downloaded a month ago, and all the others".
	Then *Plan `json:"then,omitempty"`
}

// Parse translates a sentence. now is the current time, used for relative dates.
func (p *Parser) Parse(sentence string, now time.Time) Result {
	a := p.analyze(sentence, now)
	return Result{Plan: a.plan, Then: a.then}
}

// analysis is everything read in one sentence. The conversation needs more
// than the plan: whether the sentence was an order, and every date it named.
type analysis struct {
	sentence string
	toks     []token
	plan     Plan
	then     *Plan
	dates    []DateRange
	// hasCommand is set when the sentence starts a search ("trouve-moi ...").
	hasCommand bool
	// description is what remains once every rule has taken its words.
	description string
	terms       []Term
	written     bool
	origin      Origin
}

func (p *Parser) analyze(sentence string, now time.Time) *analysis {
	a := &analysis{sentence: sentence, toks: tokenize(sentence)}
	a.plan.Sort = SortRelevance
	a.plan.Cardinality = CardinalityMany
	if len(a.toks) == 0 {
		return a
	}

	alsoAll := a.extractAlsoAll()
	a.extractCodes()
	a.dates = extractDates(sentence, a.toks, now)
	if len(a.dates) > 0 {
		first := a.dates[0]
		a.plan.Date = &first
	}
	a.extractPosition()
	a.extractSource()
	a.extractKinds()
	a.extractDuration()
	a.extractSort()
	a.extractEverything()
	a.extractWritten()
	a.annotatePeople()
	a.annotateFeatures()
	a.extractFillers()
	a.buildDescription(p.lexicon)

	if alsoAll {
		then := a.plan.Clone()
		then.Date, then.Source, then.ExcludeDates = nil, nil, nil
		then.Cardinality = CardinalityAll
		then.Sort = SortNewest
		a.then = &then
	}
	return a
}

// scan applies patterns over the free words of the sentence, longest match
// first, and calls found for each match. found returns false to leave the
// words untouched.
func (a *analysis) scan(patterns []pattern, found func(index, i, length int) bool) {
	for i := 0; i < len(a.toks); i++ {
		if a.toks[i].used {
			continue
		}
		length, _, index, ok := findFirst(patterns, a.toks, i)
		if !ok || !found(index, i, length) {
			continue
		}
		use(a.toks, i, length)
		i += length - 1
	}
}

func (a *analysis) origin2(i, length int, rule string) Origin {
	return originOf(a.sentence, a.toks, i, length, rule)
}

// extractAlsoAll recognizes a second request at the end of the sentence:
// "..., et tous ceux qui sont dans ma bibliothèque".
func (a *analysis) extractAlsoAll() bool {
	for i, t := range a.toks {
		if t.norm != "et" && t.norm != "ainsi" && t.norm != "plus" {
			continue
		}
		window := a.toks[i+1 : min(i+6, len(a.toks))]
		all, others := false, false
		for _, w := range window {
			switch w.norm {
			case "tous", "toutes", "tout":
				all = true
			case "ceux", "celles", "autres", "reste":
				others = true
			}
		}
		if all && others {
			use(a.toks, i, len(a.toks)-i)
			return true
		}
	}
	return false
}

var codePatterns = compileAll(
	"qr code|codes",
	"qrcode|qrcodes|flashcode|flashcodes|codebarre|codebarres",
	"code|codes qr",
	"q r code|codes",
	// What speech recognition makes of "QR code".
	"cure|cuir|cul|ku|kiu|cu air|ar|er|r? code|codes",
	"code|codes a? barre|barres",
)

func (a *analysis) extractCodes() {
	a.scan(codePatterns, func(_, i, length int) bool {
		if !a.plan.HasFeature(FeatureCode) {
			a.plan.Features = append(a.plan.Features, Feature{Name: FeatureCode, Origin: a.origin2(i, length, "feature:code")})
		}
		return true
	})
}

var (
	positionEnd = compileAll(
		"tout? a la fin",
		"en fin",
		"a|en|sur? la? derniere page",
		"tout? en bas de la? page",
		"tout? en bas",
		"en fin de page|document",
	)
	positionStart = compileAll(
		"tout? au debut",
		"en debut",
		"a|en|sur? la? premiere page",
		"tout? en haut de la? page",
		"tout? en haut",
		"en tete",
	)
)

func (a *analysis) extractPosition() {
	set := func(where Position) func(_, i, length int) bool {
		return func(_, i, length int) bool {
			if a.plan.Position == nil {
				a.plan.Position = &PositionHint{Where: where, Origin: a.origin2(i, length, "position")}
			}
			return true
		}
	}
	a.scan(positionEnd, set(PositionEnd))
	a.scan(positionStart, set(PositionStart))
	a.extractPage()
}

// extractPage reads "à la deuxième page", "page 3", "en page 12".
func (a *analysis) extractPage() {
	for i := range a.toks {
		if a.toks[i].used || a.toks[i].norm != "page" || a.plan.Position != nil {
			continue
		}
		from, to, page := i, i, 0
		if i > 0 && !a.toks[i-1].used {
			if n, ok := ordinals[a.toks[i-1].norm]; ok && n > 0 {
				from, page = i-1, n
			}
		}
		if page == 0 && i+1 < len(a.toks) {
			if n, length, ok := parseNumber(a.toks, i+1); ok && length == 1 && n > 0 {
				to, page = i+1, n
			}
		}
		if page == 0 {
			continue
		}
		for from > 0 && !a.toks[from-1].used && in(pageLead, a.toks[from-1].norm) {
			from--
		}
		a.plan.Position = &PositionHint{Where: PositionPage, Page: page, Origin: a.origin2(from, to-from+1, "position")}
		use(a.toks, from, to-from+1)
	}
}

var pageLead = wordSet("a en sur la")

var (
	sourceDownload = compileAll(
		"telecharge|telechargee|telecharges|telechargees|telechargement|telechargements|downloade|download|downloads",
	)
	sourceMessaging = compileAll(
		"recu|recue|recus|recues|envoye|envoyee|envoyes|envoyees? par|sur|via|de|dans? whatsapp|telegram|messenger",
	)
)

func (a *analysis) extractSource() {
	set := func(source Source) func(_, i, length int) bool {
		return func(_, i, length int) bool {
			if a.plan.Source == nil {
				a.plan.Source = &SourceFilter{Source: source, Origin: a.origin2(i, length, "source")}
			}
			return true
		}
	}
	a.scan(sourceDownload, set(SourceDownload))
	a.scan(sourceMessaging, set(SourceMessaging))
}

type kindRule struct {
	spec   string
	kind   Kind
	source Source
}

var kindRules = []kindRule{
	{spec: "capture|captures d ecran", kind: KindImage, source: SourceScreenshot},
	{spec: "screenshot|screenshots|capture|captures", kind: KindImage, source: SourceScreenshot},
	{spec: "memo|memos|note|notes|message|messages vocal|vocale|vocaux|vocales", kind: KindAudio},
	{spec: "p d f", kind: KindPDF},
	// "pédéhef" is what speech recognition makes of "PDF".
	{spec: "pdf|pdfs|pedehef|document|documents|doc|docs", kind: KindPDF},
	{spec: "photo|photos|image|images|cliche|cliches|photographie|photographies|selfie|selfies", kind: KindImage},
	{spec: "video|videos|film|films|clip|clips", kind: KindVideo},
	{spec: "audio|audios|enregistrement|enregistrements", kind: KindAudio},
	// A "file" is no kind in particular, but the word must not be searched.
	{spec: "fichier|fichiers"},
}

var kindPatterns = func() []pattern {
	out := make([]pattern, len(kindRules))
	for i, r := range kindRules {
		out[i] = compile(r.spec)
	}
	return out
}()

func wordSet(words string) map[string]struct{} {
	set := make(map[string]struct{})
	for _, w := range strings.Fields(words) {
		set[w] = struct{}{}
	}
	return set
}

var (
	adverbs       = wordSet("assez tres plutot vraiment super bien trop si hyper")
	adjLarge      = wordSet("volumineux volumineuse volumineuses gros grosse grosses lourd lourde lourds lourdes enorme enormes")
	adjSmall      = wordSet("leger legere legers legeres")
	adjShort      = wordSet("petit petite petits petites court courte courts courtes bref breve")
	adjLong       = wordSet("long longue longs longues")
	adjNewest     = wordSet("dernier derniere derniers dernieres")
	adjOldest     = wordSet("vieux vieille vieilles ancien ancienne anciens anciennes premier premiere")
	singularWords = wordSet("le la l un une ce cet cette mon ma")
	pluralWords   = wordSet("les des mes ces plusieurs quelques tous toutes")
)

func in(set map[string]struct{}, word string) bool {
	_, ok := set[word]
	return ok
}

// extractKinds reads the kinds of files asked for, and the adjectives stuck to
// them: "petite vidéo", "PDF assez volumineux", "la dernière photo". An
// adjective only counts next to the kind word, so that "un gros chien" stays a
// description.
func (a *analysis) extractKinds() {
	var first, last = -1, -1
	a.scan(kindPatterns, func(index, i, length int) bool {
		r := kindRules[index]
		if r.kind != "" {
			if !a.plan.HasKind(r.kind) {
				a.plan.Kinds = append(a.plan.Kinds, r.kind)
			}
			if first < 0 {
				first = i
			}
			last = i + length - 1
		}
		if r.source != "" && a.plan.Source == nil {
			a.plan.Source = &SourceFilter{Source: r.source, Origin: a.origin2(i, length, "source")}
		}

		before := i - 1
		if before >= 0 && a.adjective(before, r.kind) {
			before--
			// "les trois dernières vidéos": the number is how many are wanted.
			if before >= 0 && a.plan.Sort != SortRelevance {
				if n, length, ok := parseNumber(a.toks, before); ok && length == 1 && n > 1 {
					a.plan.Limit = n
					a.toks[before].used = true
					before--
				}
			}
			if before >= 0 && !a.toks[before].used && in(adverbs, a.toks[before].norm) {
				a.toks[before].used = true
				before--
			}
		}
		after := i + length
		if after < len(a.toks) && !a.toks[after].used && in(adverbs, a.toks[after].norm) &&
			after+1 < len(a.toks) && a.adjective(after+1, r.kind) {
			a.toks[after].used = true
		} else if after < len(a.toks) {
			a.adjective(after, r.kind)
		}

		plural := strings.HasSuffix(a.toks[i+length-1].norm, "s") || strings.HasSuffix(a.toks[i+length-1].norm, "x")
		switch {
		case plural:
			a.plan.Cardinality = CardinalityMany
		case before >= 0 && in(pluralWords, a.toks[before].norm):
			a.plan.Cardinality = CardinalityMany
		case before >= 0 && in(singularWords, a.toks[before].norm):
			a.plan.Cardinality = CardinalityOne
		}
		return true
	})
	if first >= 0 {
		origin := a.origin2(first, last-first+1, "kinds")
		a.plan.KindsOrigin = &origin
	}
}

// adjective reads the word at position i as a size, a duration or an order, and
// takes it. It reports whether the word was one.
func (a *analysis) adjective(i int, kind Kind) bool {
	if i < 0 || i >= len(a.toks) || a.toks[i].used {
		return false
	}
	word := a.toks[i].norm
	timed := kind == KindVideo || kind == KindAudio
	origin := a.origin2(i, 1, "adjective")
	switch {
	case in(adjLarge, word):
		a.plan.Size = &Hint{Level: LevelLarge, Origin: origin}
	case in(adjSmall, word):
		a.plan.Size = &Hint{Level: LevelSmall, Origin: origin}
	case in(adjShort, word) && timed:
		a.plan.Duration = &Hint{Level: LevelSmall, Origin: origin}
	case in(adjShort, word):
		a.plan.Size = &Hint{Level: LevelSmall, Origin: origin}
	case in(adjLong, word) && timed:
		a.plan.Duration = &Hint{Level: LevelLarge, Origin: origin}
	case in(adjLong, word):
		a.plan.Size = &Hint{Level: LevelLarge, Origin: origin}
	case in(adjNewest, word):
		a.plan.Sort = SortNewest
	case in(adjOldest, word):
		a.plan.Sort = SortOldest
	default:
		return false
	}
	a.toks[i].used = true
	return true
}

var sortRules = []struct {
	spec string
	sort Sort
}{
	{"le|la|les? plus recent|recente|recents|recentes", SortNewest},
	{"le|la|les? plus ancien|ancienne|anciens|anciennes|vieux|vieille|vieilles", SortOldest},
	{"le|la|les? plus gros|grosse|grosses|lourd|lourde|volumineux|volumineuse|grand|grande|long|longue", SortLargest},
	{"le|la|les? plus petit|petite|leger|legere|court|courte", SortSmallest},
	{"recent|recente|recents|recentes|recemment|dernierement", SortNewest},
	// Dates and positions have already taken their own "dernier": what is left
	// qualifies the file itself, as in "le dernier relevé de compte".
	{"dernier|derniere|derniers|dernieres", SortNewest},
	// Said during a conversation, about the results on screen.
	{"avant ca|cela", SortOldest},
	{"apres ca|cela", SortNewest},
	{"plus tot", SortOldest},
	{"plus tard", SortNewest},
}

var sortPatterns = func() []pattern {
	out := make([]pattern, len(sortRules))
	for i, r := range sortRules {
		out[i] = compile(r.spec)
	}
	return out
}()

func (a *analysis) extractSort() {
	a.scan(sortPatterns, func(index, _, _ int) bool {
		a.plan.Sort = sortRules[index].sort
		return true
	})
}

var (
	durationLong = compileAll(
		"de? plus de|d <n> minute|minutes|min|heure|heures",
	)
	durationShort = compileAll(
		"de? moins de|d <n> minute|minutes|min|seconde|secondes|sec",
		"de? <n> seconde|secondes|sec",
		"de? quelques secondes",
	)
)

// extractDuration reads a length given in minutes or seconds.
func (a *analysis) extractDuration() {
	set := func(level Level) func(_, i, length int) bool {
		return func(_, i, length int) bool {
			a.plan.Duration = &Hint{Level: level, Origin: a.origin2(i, length, "duration")}
			return true
		}
	}
	a.scan(durationLong, set(LevelLarge))
	a.scan(durationShort, set(LevelSmall))
	// "pas trop longue" only speaks of a duration for something that has one.
	if a.plan.HasKind(KindVideo) || a.plan.HasKind(KindAudio) {
		a.scan(notTooLong, set(LevelSmall))
	}
	pages := func(level Level) func(_, i, length int) bool {
		return func(_, i, length int) bool {
			a.plan.Size = &Hint{Level: level, Origin: a.origin2(i, length, "size")}
			return true
		}
	}
	a.scan(manyPages, pages(LevelLarge))
	a.scan(fewPages, pages(LevelSmall))
}

var (
	notTooLong = compileAll("pas trop|tres|super long|longue|longs|longues")
	manyPages  = compileAll("de? plus de|d <n> page|pages", "de? plusieurs pages")
	fewPages   = compileAll("de? moins de|d <n> page|pages", "d|de? une seule? page")
)

var everythingPatterns = compileAll(
	"tous|toutes les|mes|ces",
	"l ensemble de|des",
	"la totalite de|des",
)

// extractEverything reads "tous les", "toutes mes": every match is wanted.
func (a *analysis) extractEverything() {
	a.scan(everythingPatterns, func(_, _, _ int) bool {
		a.plan.Cardinality = CardinalityAll
		return true
	})
}

var markedLead = wordSet("avec a est ou")

var writtenWords = wordSet("mot mots inscription inscriptions ecrit ecrite ecrits ecrites inscrit inscrite inscrits texte")

// extractWritten reads "le mot ...", "où il y a écrit ...": the user is after
// words visible in a picture, not after what the picture shows.
func (a *analysis) extractWritten() {
	for i := range a.toks {
		if a.toks[i].used {
			continue
		}
		word := a.toks[i].norm
		// "marqué" only after a verb or "avec": "la marque" is a brand.
		marked := (word == "marque" || word == "marquee") && i > 0 && in(markedLead, a.toks[i-1].norm)
		if in(writtenWords, word) || marked {
			a.toks[i].used = true
			a.written = true
		}
	}
}

var (
	personNouns = wordSet("personne personnes gens homme hommes femme femmes enfant enfants bebe bebes fille filles " +
		"garcon garcons monsieur messieurs dame dames ami amis amie amies copain copains copine copines adulte adultes " +
		"visage visages tete tetes individu individus mec mecs gars invite invites collegue collegues")
	pairNouns  = wordSet("couple couples")
	groupNouns = wordSet("famille groupe foule equipe bande")
)

var weAre = compileAll("on est|etait <n>", "nous sommes|etions <n>")

// annotatePeople counts the people named in the sentence: "5 personnes",
// "un homme avec une femme", "un couple". The words stay in the description,
// because the visual model understands them too; the count only adds a way to
// rank pictures by the number of faces found when they were indexed.
func (a *analysis) annotatePeople() {
	total, first, last := 0, -1, -1
	group := false
	note := func(i, end int) {
		if first < 0 {
			first = i
		}
		last = end
	}
	for i := 0; i < len(a.toks); i++ {
		if a.toks[i].used {
			continue
		}
		if in(groupNouns, a.toks[i].norm) {
			group = true
			note(i, i)
			continue
		}
		// "on est quatre", "nous étions cinq"
		if length, c, _, ok := findFirst(weAre, a.toks, i); ok {
			total += c.n
			note(i, i+length-1)
			i += length - 1
			continue
		}
		n, length, ok := parseNumber(a.toks, i)
		if !ok || i+length >= len(a.toks) || a.toks[i+length].used {
			continue
		}
		if i > 0 && a.toks[i-1].norm == "sans" {
			continue
		}
		noun := a.toks[i+length].norm
		switch {
		case in(personNouns, noun):
			total += n
		case in(pairNouns, noun):
			total += 2 * n
		default:
			continue
		}
		note(i, i+length)
		i += length
	}
	if first < 0 {
		return
	}
	origin := a.origin2(first, last-first+1, "people")
	switch {
	case total >= 2:
		a.plan.People = &Count{Value: total, Tolerance: 1, Origin: origin}
	case group:
		a.plan.People = &Count{Value: 3, AtLeast: true, Origin: origin}
	case total == 1:
		a.plan.People = &Count{Value: 1, AtLeast: true, Origin: origin}
	}
}

var (
	portraitWords  = wordSet("portrait portraits selfie selfies")
	signatureWords = wordSet("signature signatures signe signee signes signees paraphe paraphes")
)

// annotateFeatures notes facts the index knows about a file. The words stay in
// the description.
func (a *analysis) annotateFeatures() {
	for i, t := range a.toks {
		switch {
		case in(portraitWords, t.norm) && !a.plan.HasFeature(FeaturePortrait):
			origin := a.origin2(i, 1, "feature:portrait")
			a.plan.Features = append(a.plan.Features, Feature{Name: FeaturePortrait, Origin: origin})
			if a.plan.People == nil {
				a.plan.People = &Count{Value: 1, AtLeast: true, Origin: origin}
			}
		case in(signatureWords, t.norm) && !a.plan.HasFeature(FeatureSignature):
			a.plan.Features = append(a.plan.Features, Feature{Name: FeatureSignature, Origin: a.origin2(i, 1, "feature:signature")})
		}
	}
}

// Orders only count at the start of the sentence: in "une photo de ma montre",
// "montre" is a watch.
var commandPatterns = compileAll(
	"trouve|trouves|trouver|retrouve|retrouver|retourne|retourner|montre|montrer|cherche|chercher|recherche|rechercher|"+
		"donne|donner|affiche|afficher|sors|ressors|renvoie|ouvre|liste|lister moi|nous?",
	"je veux|voudrais|cherche|recherche|souhaite|souhaiterais voir|trouver|retrouver|avoir?",
	"j aimerais voir|trouver|retrouver|avoir?",
	"est ce que tu peux|pourrais",
	"peux|pourrais tu",
	"tu peux|pourrais",
	"j ai besoin de|d",
	"il me faut",
	"ou est|sont passe|passee|passes|passees?",
	"t|tu aurais|as pas?",
	// Pronouns between two parts of an order: "tu peux me retrouver".
	"me|m|moi|nous",
)

var fillerPatterns = compileAll(
	"s il te|vous plait",
	"stp|svp|merci|bonjour|salut",
	// Hesitations, frequent when the sentence is dictated.
	"euh|heu|hum|hmm|ben|bah|alors|ok|oui|voila|bref",
	"qui contient|contiennent|contenait|montre|montrent|represente|representent|comporte|comportent|presente|presentent",
	"qui parle|parlent|traite|traitent de|d|du|des",
	"qui correspond|correspondent|ressemble|ressemblent a|au|aux",
	"qui concerne|concernent",
	"contenant|montrant|representant|concernant|mentionnant|comportant",
	"ou il y a|avait",
	"dans lequel|laquelle|lesquels|lesquelles il y a|avait",
	"il y a|avait",
	"ou on|je voit|vois|apercoit|apercois|distingue apparaitre?",
	"ou l on voit|apercoit|distingue apparaitre?",
	"dans lequel|laquelle|lesquels|lesquelles on|je voit|vois|apercoit|apercois|distingue apparaitre?",
	"on|je voit|vois|apercoit|apercois|distingue apparaitre?",
	"ou on|je peut|peux voir apparaitre?",
	"on|je peut|peux voir apparaitre?",
	"ou il est question de|d|du|des?",
	"il est question de|d|du|des?",
	"ou? je|on|ca|il|elle parle|parlent|parlait de|d|du|des",
	"au sujet de|d|du|des",
	"a propos de|d|du|des",
	"qui apparait|apparaissent",
	"apparait|apparaitre|apparaissent",
	"que j ai|avais pris|prise|prises|fait|faite|faites|filme|filmee|enregistre|enregistree|sauvegarde|sauvegardee|garde|gardee|recu|recue|eu?",
	"j ai|avais",
	"qui est|sont dans|sur ma|mon|la|le bibliotheque|telephone|galerie|appareil|pellicule|portable",
	"qui se trouve|trouvent dans|sur ma|mon|la|le bibliotheque|telephone|galerie|appareil|pellicule|portable",
	"dans|sur|de ma|mon|la|le bibliotheque|telephone|galerie|appareil|pellicule|portable",
)

func (a *analysis) extractFillers() {
	for i := 0; i < len(a.toks); i++ {
		if a.toks[i].used {
			continue
		}
		// An order is only read while nothing meaningful came before it.
		if length, _, _, ok := findFirst(commandPatterns, a.toks, i); ok {
			a.hasCommand = true
			use(a.toks, i, length)
			i += length - 1
			continue
		}
		break
	}
	a.scan(fillerPatterns, func(_, _, _ int) bool { return true })
}

var (
	// stopWords carry no meaning on their own.
	stopWords = wordSet("le la les l un une des de du d a au aux et ou en dans sur avec pour par mon ma mes ton ta tes " +
		"son sa ses notre nos ce cet cette ces qui que qu quoi dont est sont il elle ils elles on je tu y se s n ne pas " +
		"plus tres assez c ca cela celui celle ceux celles j ai m me moi t te toi comme tout tous toute toutes chez vers")
	// leadWords cannot start a description.
	leadWords = wordSet("de d du a au aux avec et ou en dans sur pour par qui que qu dont est sont il elle on je tu y " +
		"se s n ne c ca cela celui celle ceux celles j ai m me moi t te comme pas passe passee aurais aurait as avez")
	// trailWords cannot end one.
	trailWords = wordSet("de d du a au aux avec et ou en dans sur pour par qui que qu dont est sont il elle on je tu y " +
		"se s n ne c ca cela celui celle ceux celles j ai m me moi t te comme le la les l un une des mon ma mes ce cet cette ces " +
		// How the file was made or received says nothing about what it shows.
		"pris prise prises fait faite faites recu recue recus recues scanne scannee scannes scannees filme filmee filmes " +
		"envoye envoyee envoyes enregistre enregistree sauvegarde sauvegardee dessus dessous")
)

// buildDescription gathers the words no rule took: they are the free meaning
// of the sentence. Depending on what is searched, they become a visual
// description, words to find in the text, or both.
func (a *analysis) buildDescription(lexicon *Lexicon) {
	var (
		parts      []string
		start, end = -1, -1
	)
	seen := make(map[string]struct{})
	for i := 0; i < len(a.toks); {
		if a.toks[i].used {
			i++
			continue
		}
		j := i
		for j < len(a.toks) && !a.toks[j].used {
			j++
		}
		from, to := i, j // run is [from, to)
		i = j
		for from < to && in(leadWords, a.toks[from].norm) {
			from++
		}
		for to > from && in(trailWords, a.toks[to-1].norm) {
			to--
		}
		meaningful := false
		for k := from; k < to; k++ {
			if !in(stopWords, a.toks[k].norm) {
				meaningful = true
				break
			}
		}
		if !meaningful {
			continue
		}
		parts = append(parts, a.sentence[a.toks[from].start:a.toks[to-1].end])
		if start < 0 {
			start = a.toks[from].start
		}
		end = a.toks[to-1].end

		words := make([]string, 0, to-from)
		for k := from; k < to; k++ {
			words = append(words, a.toks[k].norm)
		}
		for k := 0; k < len(words); {
			term, length, ok := lexicon.match(words, k)
			if !ok {
				length = 1
				tok := a.toks[from+k]
				if in(stopWords, tok.norm) || len(tok.norm) < 2 {
					k++
					continue
				}
				term = Term{Value: strings.ToLower(a.sentence[tok.start:tok.end])}
			}
			k += length
			key := normalize(term.Value)
			if _, dup := seen[key]; dup {
				continue
			}
			seen[key] = struct{}{}
			a.terms = append(a.terms, term)
		}
	}
	if len(parts) == 0 {
		return
	}
	a.description = strings.Join(parts, ", ")
	a.origin = Origin{Start: start, End: end, Text: a.description, Rule: "description"}
	a.applyDescription()
}

// applyDescription decides what the free words are compared with.
func (a *analysis) applyDescription() {
	plan := &a.plan
	pictures := len(plan.Kinds) == 0 || plan.HasKind(KindImage) || plan.HasKind(KindVideo)
	documents := len(plan.Kinds) == 0 || plan.HasKind(KindPDF) || plan.HasKind(KindAudio)

	text := func(scope TextScope, weak bool) {
		if len(a.terms) == 0 {
			return
		}
		plan.Text = &TextQuery{
			Terms: slices.Clone(a.terms), Scope: scope, Weak: weak,
			Phrase: a.description, Origin: a.origin,
		}
	}
	visual := func() {
		plan.Visual = &Visual{Description: a.description, Origin: a.origin}
	}
	switch {
	case a.written:
		// Words to read in the picture: looking for what the picture shows
		// would bring back grass for the word "herbe".
		text(ScopeWritten, false)
	case pictures && documents:
		visual()
		text(ScopeAny, false)
	case pictures:
		visual()
		// A file name or recognized text may hold the same words.
		text(ScopeAny, true)
	default:
		text(ScopeAny, false)
	}
}
