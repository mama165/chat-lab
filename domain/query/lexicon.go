package query

import (
	"encoding/json"
	"fmt"
	"io"
	"sort"
	"strings"
)

// Lexicon lists expressions that documents rarely write the way people say
// them. "My electricity supplier" is never printed on a bill: the name of the
// company is. An entry maps what is said to what must be searched.
//
// The lexicon is personal: the default one covers common household papers, and
// Add or Load extends it with the user's own suppliers, bank or insurer.
type Lexicon struct {
	// entries are kept longest expression first, so that "fournisseur
	// d'électricité" wins over "électricité". They are only changed by Add:
	// once filled, a lexicon can be read by several searches at once.
	entries []lexiconEntry
}

type lexiconEntry struct {
	words        []string
	value        string
	alternatives []string
}

// LexiconEntry is the file format of one entry.
type LexiconEntry struct {
	// Phrase is the expression as the user says it.
	Phrase string `json:"phrase"`
	// Value is the word to search first; empty means the phrase itself.
	Value string `json:"value,omitempty"`
	// Alternatives are other wordings accepted in a document.
	Alternatives []string `json:"alternatives,omitempty"`
}

// Add registers an expression. A later entry for the same expression replaces
// the earlier one.
func (l *Lexicon) Add(phrase, value string, alternatives ...string) {
	words := strings.Fields(normalize(phrase))
	if len(words) == 0 {
		return
	}
	if value == "" {
		value = strings.ToLower(strings.TrimSpace(phrase))
	}
	entry := lexiconEntry{words: words, value: value, alternatives: alternatives}
	for i, existing := range l.entries {
		if strings.Join(existing.words, " ") == strings.Join(words, " ") {
			l.entries[i] = entry
			return
		}
	}
	l.entries = append(l.entries, entry)
	sort.SliceStable(l.entries, func(a, b int) bool { return len(l.entries[a].words) > len(l.entries[b].words) })
}

// Load reads entries from a JSON array and adds them.
func (l *Lexicon) Load(r io.Reader) error {
	var entries []LexiconEntry
	if err := json.NewDecoder(r).Decode(&entries); err != nil {
		return fmt.Errorf("reading lexicon: %w", err)
	}
	for _, e := range entries {
		l.Add(e.Phrase, e.Value, e.Alternatives...)
	}
	return nil
}

// match returns the longest entry starting at word i.
func (l *Lexicon) match(words []string, i int) (Term, int, bool) {
	if l == nil {
		return Term{}, 0, false
	}
	for _, e := range l.entries {
		if i+len(e.words) > len(words) {
			continue
		}
		matched := true
		for k, w := range e.words {
			if singular(words[i+k]) != singular(w) {
				matched = false
				break
			}
		}
		if matched {
			return Term{Value: e.value, Alternatives: append([]string(nil), e.alternatives...)}, len(e.words), true
		}
	}
	return Term{}, 0, false
}

// singular drops a plural ending, so that "fiches de paie" matches "fiche de paie".
func singular(word string) string {
	if len(word) > 3 && (strings.HasSuffix(word, "s") || strings.HasSuffix(word, "x")) {
		return word[:len(word)-1]
	}
	return word
}

// DefaultLexicon covers common French household papers.
func DefaultLexicon() *Lexicon {
	l := &Lexicon{}
	l.Add("fournisseur d'électricité", "électricité", "edf", "engie", "totalenergies", "total energies", "enedis", "ekwateur", "vattenfall", "kwh")
	l.Add("électricité", "électricité", "edf", "engie", "totalenergies", "enedis", "kwh", "électrique")
	l.Add("fournisseur de gaz", "gaz", "grdf", "engie", "totalenergies")
	l.Add("fournisseur d'accès", "internet", "orange", "free", "sfr", "bouygues", "box")
	l.Add("opérateur", "opérateur", "orange", "free", "sfr", "bouygues", "sosh", "red")
	l.Add("lave-vaisselle", "lave-vaisselle", "lave vaisselle", "dishwasher", "geschirrspüler")
	l.Add("lave-linge", "lave-linge", "lave linge", "machine à laver", "washing machine", "waschmaschine")
	l.Add("machine à laver", "machine à laver", "lave-linge", "lave linge", "washing machine")
	l.Add("sèche-linge", "sèche-linge", "sèche linge", "dryer", "trockner")
	l.Add("notice", "notice", "mode d'emploi", "manuel", "guide d'utilisation", "manual", "user guide", "bedienungsanleitung")
	l.Add("mode d'emploi", "mode d'emploi", "notice", "manuel", "guide d'utilisation", "manual")
	l.Add("notaire", "notaire", "notarié", "notariale", "notarial", "office notarial", "étude notariale")
	l.Add("facture", "facture", "invoice", "avis d'échéance")
	l.Add("fiche de paie", "bulletin de paie", "bulletin de salaire", "fiche de paie")
	l.Add("bulletin de paie", "bulletin de paie", "bulletin de salaire", "fiche de paie")
	l.Add("bulletin de salaire", "bulletin de salaire", "bulletin de paie", "fiche de paie")
	l.Add("impôts", "impôts", "impôt", "avis d'imposition", "dgfip", "finances publiques")
	l.Add("avis d'imposition", "avis d'imposition", "impôt", "dgfip")
	l.Add("relevé bancaire", "relevé de compte", "relevé bancaire", "relevé d'opérations")
	l.Add("relevé de compte", "relevé de compte", "relevé bancaire", "relevé d'opérations")
	l.Add("rib", "rib", "relevé d'identité bancaire", "iban")
	l.Add("loyer", "loyer", "quittance")
	l.Add("carte d'identité", "carte nationale d'identité", "carte d'identité", "cni")
	l.Add("permis de conduire", "permis de conduire")
	l.Add("carte grise", "certificat d'immatriculation", "carte grise")
	l.Add("mutuelle", "mutuelle", "complémentaire santé")
	l.Add("bail", "bail", "contrat de location")
	return l
}
