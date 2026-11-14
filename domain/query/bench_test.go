package query

import (
	"bufio"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"
	"time"
)

// TestBenchmark measures a source of plans against the owner's reference set.
//
// It is skipped unless QUERY_BENCH names a JSON Lines file, one object per line:
//
//	{"sentence": "...", "plan": {...}}     plan written by a language model, as JSON
//	{"sentence": "...", "rules": true}     the plan of the rules (baseline)
//
// Each plan goes through ParseJSON, which refuses anything untraceable, and the
// fields of the reference that the plan format knows are compared. Fields the
// format does not yet hold (place, page count, exclusions) are not scored: they
// are counted separately, so a result is never flattered by what cannot be read.
//
//	QUERY_BENCH=run.jsonl QUERY_BENCH_SET=testdata/phrases_cible.json go test -run TestBenchmark -v ./domain/query/
func TestBenchmark(t *testing.T) {
	outputs := os.Getenv("QUERY_BENCH")
	if outputs == "" {
		t.Skip("set QUERY_BENCH to measure a source of plans")
	}
	set := os.Getenv("QUERY_BENCH_SET")
	if set == "" {
		set = "testdata/phrases_cible.json"
	}
	reference := loadReference(t, set)

	file, err := os.Open(outputs)
	if err != nil {
		t.Fatal(err)
	}
	defer file.Close()

	parser := NewParser()
	var total, refused, ok, correct, unscored int
	var elapsed time.Duration
	scanner := bufio.NewScanner(file)
	scanner.Buffer(make([]byte, 1<<20), 1<<20)
	for scanner.Scan() {
		var line struct {
			Sentence string          `json:"sentence"`
			Rules    bool            `json:"rules"`
			Plan     json.RawMessage `json:"plan"`
			Seconds  float64         `json:"seconds"`
		}
		if err := json.Unmarshal(scanner.Bytes(), &line); err != nil {
			t.Fatalf("bad line: %v", err)
		}
		total++
		want, known := reference[line.Sentence]
		if !known {
			t.Fatalf("sentence not in the reference set: %q", line.Sentence)
		}
		elapsed += time.Duration(line.Seconds * float64(time.Second))

		var plan Plan
		var issues []Issue
		if line.Rules {
			plan = parser.Parse(line.Sentence, testNow).Plan
		} else {
			plan, issues, err = ParseJSON(line.Plan, testNow, line.Sentence)
			if err != nil {
				refused++
				t.Logf("refused %q: %v", line.Sentence, issues)
				continue
			}
		}
		ok++
		diffs := differences(Result{Plan: plan}, want.expectation)
		if len(diffs) == 0 {
			correct++
		} else {
			t.Logf("wrong %q\n  %s\n  %v", line.Sentence, plan.Summary(), diffs)
		}
		unscored += len(want.unknown)
	}
	if err := scanner.Err(); err != nil {
		t.Fatal(err)
	}

	report := []string{
		fmt.Sprintf("sentences:          %d", total),
		fmt.Sprintf("refused as invented: %d", refused),
		fmt.Sprintf("plans accepted:      %d", ok),
		fmt.Sprintf("fully right:         %d (%.0f %%)", correct, pct(correct, total)),
		fmt.Sprintf("criteria not scored: %d (the format does not hold them yet)", unscored),
	}
	if total > 0 && elapsed > 0 {
		report = append(report, fmt.Sprintf("mean time:          %v", elapsed/time.Duration(total)))
	}
	t.Log("\n" + strings.Join(report, "\n"))
}

func pct(part, whole int) float64 {
	if whole == 0 {
		return 0
	}
	return 100 * float64(part) / float64(whole)
}

type referenceEntry struct {
	expectation expectation
	unknown     []string
}

// knownWant lists the keys of the reference that the plan format can express.
var knownWant = map[string]bool{
	"kinds": true, "visual": true, "no_visual": true, "terms": true, "scope": true,
	"weak_text": true, "no_text": true, "date": true, "no_date": true, "people": true,
	"people_at_least": true, "features": true, "size": true, "no_size": true,
	"duration": true, "position": true, "source": true, "sort": true,
	"cardinality": true, "then": true,
}

func loadReference(t *testing.T, path string) map[string]referenceEntry {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	var cases []struct {
		Sentence string         `json:"sentence"`
		Want     map[string]any `json:"want"`
	}
	if err := json.Unmarshal(data, &cases); err != nil {
		t.Fatal(err)
	}
	out := make(map[string]referenceEntry, len(cases))
	for _, c := range cases {
		raw, _ := json.Marshal(c.Want)
		var e expectation
		if err := json.Unmarshal(raw, &e); err != nil {
			t.Fatalf("%q: %v", c.Sentence, err)
		}
		var unknown []string
		for key := range c.Want {
			if !knownWant[key] {
				unknown = append(unknown, key)
			}
		}
		out[c.Sentence] = referenceEntry{expectation: e, unknown: unknown}
	}
	return out
}
