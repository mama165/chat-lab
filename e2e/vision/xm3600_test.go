// Package vision runs the whole search, from a French sentence to a ranked
// list of pictures, on a public benchmark.
//
// XM3600 holds 3 600 photographs and 8 562 descriptions of them written in
// French by people. Each description goes through the parser, the check of the
// plan, and the search with the real vision model; the photograph it was
// written for is looked for in the answer. Nothing is labelled here.
//
// The test needs the vectors of the photographs and a program serving the text
// side of the model. It is skipped unless they are given:
//
//	scripts/export_xm3600.py XM3600_DIR CACHE_DIR OUT   # once
//	XM3600_EXPORT=OUT XM3600_ENCODER="python3 scripts/encode_text.py MODELS/siglip2 --full" \
//	    XM3600_SAMPLE=2000 go test ./e2e/vision/ -run XM3600 -v -timeout 60m
package vision

import (
	"chat-lab/domain/query"
	"chat-lab/infrastructure/catalog"
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"slices"
	"sort"
	"strconv"
	"strings"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

var today = time.Date(2026, time.November, 15, 12, 0, 0, 0, time.UTC)

type benchQuery struct {
	ID     string `json:"id"`
	Text   string `json:"text"`
	Target string `json:"target"`
}

func TestXM3600(t *testing.T) {
	export := os.Getenv("XM3600_EXPORT")
	command := os.Getenv("XM3600_ENCODER")
	if export == "" || command == "" {
		t.Skip("set XM3600_EXPORT and XM3600_ENCODER to run the benchmark")
	}

	var items []catalog.Item
	readJSON(t, filepath.Join(export, "library.json"), &items)
	var queries []benchQuery
	readJSON(t, filepath.Join(export, "queries.json"), &queries)
	if sample, _ := strconv.Atoi(os.Getenv("XM3600_SAMPLE")); sample > 0 && sample < len(queries) {
		step := len(queries) / sample
		var kept []benchQuery
		for i := 0; i < len(queries) && len(kept) < sample; i += step {
			kept = append(kept, queries[i])
		}
		queries = kept
	}

	library, err := catalog.Open("")
	require.NoError(t, err)
	defer library.Close()
	require.NoError(t, library.Put(items...))

	parts := strings.Fields(command)
	encoder, err := catalog.StartProcessEncoder(parts[0], parts[1:]...)
	require.NoError(t, err)
	defer encoder.Close()

	parser := query.NewParser()
	opts := catalog.Options{
		Encoder: encoder, Now: today, Size: 50,
		VisualMargin: catalog.SigLIP2Margin, VisualFloor: catalog.SigLIP2Floor,
	}

	var (
		rank                    []int // position of the target among the hits, 0 when absent
		inMatches               int   // the target is a match, not only listed beyond
		matchCounts             []int
		invalid, nothingMatched int
		withFilters             = map[string]int{}
		examples                = map[string][]string{}
	)
	started := time.Now()
	for _, q := range queries {
		result := parser.Parse(q.Text, today)
		plan := result.Plan
		if issues := query.Validate(plan, today, q.Text); len(issues) > 0 {
			invalid++
		}
		// What the parser added to a description that only describes a picture.
		for field, present := range map[string]bool{
			"date": plan.Date != nil, "place": plan.Place != nil, "kinds": len(plan.Kinds) > 0,
			"people counted": plan.People != nil && plan.People.Counted, "setting": plan.Setting != nil,
			"exclusion": plan.Visual != nil && len(plan.Visual.Not) > 0, "source": plan.Source != nil,
			"position": plan.Position != nil, "limit": plan.Limit > 0, "language": plan.Language != nil,
		} {
			if present {
				withFilters[field]++
				if len(examples[field]) < 3 {
					examples[field] = append(examples[field], q.Text)
				}
			}
		}

		answer, err := library.Search(context.Background(), plan, opts)
		require.NoError(t, err, q.Text)
		if answer.Total == 0 {
			nothingMatched++
		}
		matchCounts = append(matchCounts, answer.Total)
		position := 0
		for i, hit := range answer.Hits {
			if hit.Item.ID == q.Target {
				position = i + 1
				if !hit.Beyond {
					inMatches++
				}
				break
			}
		}
		rank = append(rank, position)
	}

	within := func(n int) float64 {
		count := 0
		for _, r := range rank {
			if r > 0 && r <= n {
				count++
			}
		}
		return float64(count) / float64(len(rank))
	}
	sort.Ints(matchCounts)
	report := []string{
		fmt.Sprintf("descriptions:                    %d (in %v)", len(queries), time.Since(started).Round(time.Second)),
		fmt.Sprintf("target first:                    %.1f %%", 100*within(1)),
		fmt.Sprintf("target in the first 5:           %.1f %%", 100*within(5)),
		fmt.Sprintf("target in the first 10:          %.1f %%", 100*within(10)),
		fmt.Sprintf("target among the real matches:   %.1f %%", 100*float64(inMatches)/float64(len(queries))),
		fmt.Sprintf("matches per description:         median %d, 90th percentile %d", matchCounts[len(matchCounts)/2], matchCounts[len(matchCounts)*9/10]),
		fmt.Sprintf("descriptions with no match:      %.1f %%", 100*float64(nothingMatched)/float64(len(queries))),
		fmt.Sprintf("plans refused by the check:      %d", invalid),
	}
	fields := make([]string, 0, len(withFilters))
	for field := range withFilters {
		fields = append(fields, field)
	}
	slices.Sort(fields)
	for _, field := range fields {
		report = append(report, fmt.Sprintf("added by the parser: %-15s %.1f %%  e.g. %q", field,
			100*float64(withFilters[field])/float64(len(queries)), examples[field]))
	}
	t.Log("\n" + strings.Join(report, "\n"))
	b, _ := json.Marshal(report)
	_ = b
}

func readJSON(t *testing.T, path string, into any) {
	t.Helper()
	data, err := os.ReadFile(path)
	require.NoError(t, err)
	require.NoError(t, json.Unmarshal(data, into))
}
