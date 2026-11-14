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
	session := query.NewSession(query.NewParser(query.WithLexicon(lexicon)))

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
