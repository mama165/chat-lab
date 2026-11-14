// Command query shows how a sentence is understood, without any index.
//
//	go run ./cmd/query "trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle"
//
// Without a sentence, it reads lines from the terminal and keeps the thread of
// the conversation, so that refinements can be tried:
//
//	> trouve-moi une photo de cuisine
//	> pas celle de l'an dernier
//	> et en vidéo ?
//
// With a library, the plan is run and the files found are listed:
//
//	go run ./cmd/query -library infrastructure/catalog/testdata/library.json -now 2026-11-14
//
// The library is a JSON list of entries. Pictures have no vision model here:
// they are compared through their caption, which only stands in for one.
package main

import (
	"bufio"
	"chat-lab/domain/query"
	"chat-lab/infrastructure/catalog"
	"context"
	"encoding/json"
	"flag"
	"fmt"
	"io"
	"os"
	"strings"
	"time"
)

func main() {
	if err := run(os.Args[1:], os.Stdin, os.Stdout); err != nil {
		fmt.Fprintln(os.Stderr, "query:", err)
		os.Exit(1)
	}
}

func run(args []string, in io.Reader, out io.Writer) error {
	flags := flag.NewFlagSet("query", flag.ContinueOnError)
	nowFlag := flags.String("now", "", "date used as today, as 2026-11-14 (default: the real date)")
	asJSON := flags.Bool("json", false, "print the full plan as JSON")
	lexiconPath := flags.String("lexicon", "", "JSON file of personal expressions added to the default lexicon")
	candidates := flags.Bool("candidates", false, "for each line read, print the free words a model may judge, as JSON")
	libraryPath := flags.String("library", "", "JSON list of files to search, such as infrastructure/catalog/testdata/library.json")
	if err := flags.Parse(args); err != nil {
		return err
	}

	now := time.Now()
	if *nowFlag != "" {
		parsed, err := time.ParseInLocation("2006-01-02", *nowFlag, time.Local)
		if err != nil {
			return fmt.Errorf("-now must look like 2026-11-14: %w", err)
		}
		now = parsed.Add(12 * time.Hour)
	}

	lexicon := query.DefaultLexicon()
	if *lexiconPath != "" {
		file, err := os.Open(*lexiconPath)
		if err != nil {
			return err
		}
		defer file.Close()
		if err := lexicon.Load(file); err != nil {
			return err
		}
	}
	parser := query.NewParser(query.WithLexicon(lexicon))
	if *candidates {
		return printCandidates(parser, in, out, now)
	}
	session := query.NewSession(parser)
	a := &assistant{session: session, now: now, asJSON: *asJSON}
	if *libraryPath != "" {
		library, err := openLibrary(*libraryPath)
		if err != nil {
			return err
		}
		defer library.Close()
		a.library = library
	}

	if sentence := strings.TrimSpace(strings.Join(flags.Args(), " ")); sentence != "" {
		return a.answer(out, sentence)
	}

	scanner := bufio.NewScanner(in)
	fmt.Fprint(out, "> ")
	for scanner.Scan() {
		if sentence := strings.TrimSpace(scanner.Text()); sentence != "" {
			if err := a.answer(out, sentence); err != nil {
				return err
			}
		}
		fmt.Fprint(out, "> ")
	}
	fmt.Fprintln(out)
	return scanner.Err()
}

// assistant answers sentences: it keeps the conversation, and the library
// when one was given.
type assistant struct {
	session *query.Session
	library *catalog.Catalog
	now     time.Time
	asJSON  bool
	// shown is the number of results already listed for the current search.
	shown int
	last  []catalog.Hit
}

const pageSize = 5

// openLibrary loads a list of entries in a catalog kept in memory.
func openLibrary(path string) (*catalog.Catalog, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var items []catalog.Item
	if err := json.Unmarshal(data, &items); err != nil {
		return nil, fmt.Errorf("reading %s: %w", path, err)
	}
	for i := range items {
		catalog.WordEncoder{}.Describe(&items[i])
	}
	library, err := catalog.Open("")
	if err != nil {
		return nil, err
	}
	if err := library.Put(items...); err != nil {
		library.Close()
		return nil, err
	}
	return library, nil
}

func (a *assistant) answer(out io.Writer, sentence string) error {
	turn := a.session.Interpret(sentence, a.now)
	switch turn.Action {
	case query.ActionReset:
		a.shown, a.last = 0, nil
		fmt.Fprintln(out, "Nouvelle recherche.")
		return nil
	case query.ActionNone:
		fmt.Fprintln(out, "Je n'ai pas compris ce qu'il faut chercher.")
		return nil
	case query.ActionMore:
		fmt.Fprintln(out, "Suite des résultats de : "+turn.Plan.Summary())
		return a.search(out, turn.Plan, a.shown)
	case query.ActionSelect:
		return a.open(out, turn.Index)
	case query.ActionRefine:
		fmt.Fprintln(out, "Recherche affinée : "+turn.Plan.Summary())
	default:
		fmt.Fprintln(out, "Recherche : "+turn.Plan.Summary())
	}
	for _, issue := range query.Validate(turn.Plan, a.now, a.session.Sentences()...) {
		fmt.Fprintln(out, "  à vérifier —", issue)
	}
	if a.asJSON {
		encoder := json.NewEncoder(out)
		encoder.SetIndent("", "  ")
		if err := encoder.Encode(turn); err != nil {
			return err
		}
	}
	a.shown, a.last = 0, nil
	if err := a.search(out, turn.Plan, 0); err != nil {
		return err
	}
	if turn.Then != nil {
		fmt.Fprintln(out, "Puis : "+turn.Then.Summary())
		return a.search(out, *turn.Then, 0)
	}
	return nil
}

// search lists a page of the files found, when there is a library.
func (a *assistant) search(out io.Writer, plan query.Plan, offset int) error {
	if a.library == nil {
		return nil
	}
	results, err := a.library.Search(context.Background(), plan, catalog.Options{
		Encoder: catalog.WordEncoder{}, Now: a.now, Offset: offset, Size: pageSize,
	})
	if err != nil {
		return err
	}
	for _, relaxation := range results.Relaxed {
		fmt.Fprintln(out, "  "+relaxation.Message)
	}
	if offset == 0 {
		switch results.Total {
		case 0:
			fmt.Fprintln(out, "  Aucun résultat.")
		case 1:
			fmt.Fprintln(out, "  1 résultat")
		default:
			fmt.Fprintf(out, "  %d résultats\n", results.Total)
		}
	}
	for i, hit := range results.Hits {
		line := fmt.Sprintf("  %d. %s", offset+i+1, hit.Item.Name)
		if hit.Page > 0 {
			line += fmt.Sprintf(" — page %d", hit.Page)
		}
		if hit.Excerpt != "" {
			line += " — « " + hit.Excerpt + " »"
		} else if hit.Item.Caption != "" {
			line += " — " + hit.Item.Caption
		}
		for _, note := range hit.Notes {
			line += " (" + note + ")"
		}
		if hit.Beyond {
			line += " (moins proche)"
		}
		fmt.Fprintln(out, line)
	}
	a.last = append(a.last[:min(offset, len(a.last))], results.Hits...)
	a.shown = offset + len(results.Hits)
	return nil
}

// open designates one of the results listed.
func (a *assistant) open(out io.Writer, index int) error {
	if a.library == nil {
		if index < 0 {
			fmt.Fprintln(out, "Ouvrir le dernier résultat.")
		} else {
			fmt.Fprintf(out, "Ouvrir le résultat n° %d.\n", index)
		}
		return nil
	}
	if index < 0 {
		index = len(a.last)
	}
	if index < 1 || index > len(a.last) {
		fmt.Fprintln(out, "Ce résultat n'est pas dans la liste.")
		return nil
	}
	fmt.Fprintln(out, "Ouvrir : "+a.last[index-1].Item.Path)
	return nil
}

// printCandidates writes, for each sentence read, the words of its description
// that the rules could not judge. A language model is given these words and
// nothing else to decide on.
func printCandidates(parser *query.Parser, in io.Reader, out io.Writer, now time.Time) error {
	encoder := json.NewEncoder(out)
	encoder.SetEscapeHTML(false)
	scanner := bufio.NewScanner(in)
	for scanner.Scan() {
		sentence := strings.TrimSpace(scanner.Text())
		if sentence == "" {
			continue
		}
		words := parser.Candidates(sentence, now)
		if words == nil {
			words = []string{}
		}
		if err := encoder.Encode(map[string]any{"sentence": sentence, "candidates": words}); err != nil {
			return err
		}
	}
	return scanner.Err()
}
