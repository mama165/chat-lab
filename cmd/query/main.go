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
package main

import (
	"bufio"
	"chat-lab/domain/query"
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

	if sentence := strings.TrimSpace(strings.Join(flags.Args(), " ")); sentence != "" {
		return answer(out, session, sentence, now, *asJSON)
	}

	scanner := bufio.NewScanner(in)
	fmt.Fprint(out, "> ")
	for scanner.Scan() {
		if sentence := strings.TrimSpace(scanner.Text()); sentence != "" {
			if err := answer(out, session, sentence, now, *asJSON); err != nil {
				return err
			}
		}
		fmt.Fprint(out, "> ")
	}
	fmt.Fprintln(out)
	return scanner.Err()
}

func answer(out io.Writer, session *query.Session, sentence string, now time.Time, asJSON bool) error {
	turn := session.Interpret(sentence, now)
	switch turn.Action {
	case query.ActionReset:
		fmt.Fprintln(out, "Nouvelle recherche.")
		return nil
	case query.ActionNone:
		fmt.Fprintln(out, "Je n'ai pas compris ce qu'il faut chercher.")
		return nil
	case query.ActionMore:
		fmt.Fprintln(out, "Suite des résultats de : "+turn.Plan.Summary())
		return nil
	case query.ActionSelect:
		if turn.Index < 0 {
			fmt.Fprintln(out, "Ouvrir le dernier résultat.")
		} else {
			fmt.Fprintf(out, "Ouvrir le résultat n° %d.\n", turn.Index)
		}
		return nil
	case query.ActionRefine:
		fmt.Fprintln(out, "Recherche affinée : "+turn.Plan.Summary())
	default:
		fmt.Fprintln(out, "Recherche : "+turn.Plan.Summary())
	}
	if turn.Then != nil {
		fmt.Fprintln(out, "Puis : "+turn.Then.Summary())
	}
	for _, issue := range query.Validate(turn.Plan, now, session.Sentences()...) {
		fmt.Fprintln(out, "  à vérifier —", issue)
	}
	if !asJSON {
		return nil
	}
	encoder := json.NewEncoder(out)
	encoder.SetIndent("", "  ")
	return encoder.Encode(turn)
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
