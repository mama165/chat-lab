package catalog

import (
	"chat-lab/domain/query"
	"context"
	"fmt"
	"math/rand/v2"
	"os"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func document(id string, pages ...string) Item {
	return Item{ID: id, Path: "/docs/" + id + ".pdf", Name: id + ".pdf", Kind: query.KindPDF, AddedAt: testNow, Pages: pages}
}

func find(t *testing.T, c *Catalog, words string) Results {
	t.Helper()
	plan := query.NewParser().Parse(words, testNow).Plan
	results, err := c.Search(context.Background(), plan, Options{Encoder: WordEncoder{}, Now: testNow})
	require.NoError(t, err)
	return results
}

func TestCatalog_PutReplacesAnEntry(t *testing.T) {
	c, err := Open("")
	require.NoError(t, err)
	defer c.Close()
	require.NoError(t, c.Put(document("bail", "page une", "le loyer mensuel", "la caution")))

	// The file changed: one page only, and other words.
	require.NoError(t, c.Put(document("bail", "avenant au contrat")))

	require.Equal(t, 1, c.Len())
	item, ok := c.Get("bail")
	require.True(t, ok)
	require.Equal(t, 1, item.PageCount)
	// The pages of the old version are gone from the index, not only from the count.
	require.Zero(t, find(t, c, "le PDF sur le loyer").Total)
	require.Zero(t, find(t, c, "le PDF sur la caution").Total)
	require.Equal(t, 1, find(t, c, "le PDF sur l'avenant").Total)
}

func TestCatalog_Delete(t *testing.T) {
	c, err := Open("")
	require.NoError(t, err)
	defer c.Close()
	require.NoError(t, c.Put(document("bail", "le loyer", "la caution"), document("devis", "le loyer du garage")))

	require.NoError(t, c.Delete("bail", "inconnu"))

	require.Equal(t, 1, c.Len())
	_, ok := c.Get("bail")
	require.False(t, ok)
	results := find(t, c, "le PDF sur le loyer")
	require.Equal(t, []string{"devis"}, matches(results))
	require.Zero(t, find(t, c, "le PDF sur la caution").Total)
}

func TestCatalog_RefusesIncompleteEntries(t *testing.T) {
	c, err := Open("")
	require.NoError(t, err)
	defer c.Close()

	require.Error(t, c.Put(Item{Path: "/a.pdf", Kind: query.KindPDF, AddedAt: testNow}))
	require.Error(t, c.Put(Item{ID: "a", Kind: "spreadsheet", AddedAt: testNow}))
	require.Error(t, c.Put(Item{ID: "a", Kind: query.KindPDF}))
	// One bad entry stops the whole batch: nothing half-written.
	require.Error(t, c.Put(document("bon", "texte"), Item{ID: "", Kind: query.KindPDF, AddedAt: testNow}))
	require.Zero(t, c.Len())
}

func TestCatalog_SurvivesARestart(t *testing.T) {
	dir := t.TempDir()
	c, err := Open(dir)
	require.NoError(t, err)
	require.NoError(t, c.Put(loadLibrary(t)...))
	before := find(t, c, "Trouve-moi l'image qui contient une rose.")
	require.NoError(t, c.Close())

	reopened, err := Open(dir)
	require.NoError(t, err)
	defer reopened.Close()

	require.Equal(t, 40, reopened.Len())
	require.Equal(t, map[query.Kind]int{query.KindImage: 21, query.KindPDF: 14, query.KindVideo: 4, query.KindAudio: 1}, reopened.Counts())
	// Text, facts and vectors are all back.
	require.Equal(t, []string{"pdf-notaire"}, matches(find(t, reopened, "le PDF où il est question d'un notaire")))
	require.Equal(t, 3, len(matches(find(t, reopened, "tous les QR codes"))))
	after := find(t, reopened, "Trouve-moi l'image qui contient une rose.")
	require.Equal(t, matches(before), matches(after))
	require.InDelta(t, before.Hits[0].Score, after.Hits[0].Score, 1e-9)
}

func TestCatalog_RefusesADamagedVectorsFile(t *testing.T) {
	dir := t.TempDir()
	c, err := Open(dir)
	require.NoError(t, err)
	require.NoError(t, c.Put(loadLibrary(t)...))
	require.NoError(t, c.Close())

	path := filepath.Join(dir, vectorsFile)
	data, err := os.ReadFile(path)
	require.NoError(t, err)
	require.NoError(t, os.WriteFile(path, data[:len(data)/2], 0o600))
	_, err = Open(dir)
	require.Error(t, err)

	require.NoError(t, os.WriteFile(path, []byte("pas un fichier de vecteurs"), 0o600))
	_, err = Open(dir)
	require.Error(t, err)
}

func TestCatalog_VectorsAreNormalizedAndUselessOnesDropped(t *testing.T) {
	c, err := Open("")
	require.NoError(t, err)
	defer c.Close()
	item := Item{ID: "photo", Kind: query.KindImage, AddedAt: testNow, Vectors: [][]float32{{3, 4}, {0, 0}}}

	require.NoError(t, c.Put(item))

	require.Equal(t, [][]float32{{0.6, 0.8}}, c.vectors["photo"])
}

// bigLibrary builds a library of the size the owner expects: 5 000 photos,
// 300 documents of 15 pages, 300 videos of 12 frames, with vectors of the size
// the vision model produces.
func bigLibrary(tb testing.TB) *Catalog {
	tb.Helper()
	c, err := Open("")
	require.NoError(tb, err)
	tb.Cleanup(func() { c.Close() })
	rng := rand.New(rand.NewPCG(1, 2))
	vector := func() []float32 {
		v := make([]float32, 768)
		for i := range v {
			v[i] = float32(rng.NormFloat64())
		}
		return v
	}
	words := []string{"facture", "contrat", "assurance", "notaire", "électricité", "banque", "relevé", "impôts", "garantie", "loyer", "voiture", "santé"}
	var batch []Item
	flush := func() {
		require.NoError(tb, c.Put(batch...))
		batch = batch[:0]
	}
	for i := range 5000 {
		batch = append(batch, Item{
			ID: fmt.Sprintf("img-%d", i), Name: fmt.Sprintf("IMG_%04d.jpg", i), Path: "/DCIM/Camera", Kind: query.KindImage,
			AddedAt: testNow.AddDate(0, 0, -i%900), Vectors: [][]float32{vector()},
		})
		if len(batch) == 500 {
			flush()
		}
	}
	for i := range 300 {
		frames := make([][]float32, 12)
		for f := range frames {
			frames[f] = vector()
		}
		batch = append(batch, Item{
			ID: fmt.Sprintf("vid-%d", i), Name: fmt.Sprintf("VID_%04d.mp4", i), Kind: query.KindVideo,
			AddedAt: testNow.AddDate(0, 0, -i), Seconds: 30 + i%30, Vectors: frames,
		})
	}
	flush()
	for i := range 300 {
		pages := make([]string, 15)
		for p := range pages {
			text := ""
			for w := range 120 {
				text += words[(i+p*7+w*3)%len(words)] + " "
			}
			pages[p] = text
		}
		batch = append(batch, Item{
			ID: fmt.Sprintf("pdf-%d", i), Name: fmt.Sprintf("document-%d.pdf", i), Kind: query.KindPDF,
			AddedAt: testNow.AddDate(0, 0, -i*3), Pages: pages,
		})
		if len(batch) == 100 {
			flush()
		}
	}
	flush()
	return c
}

// TestSearch_SpeedOnAFullLibrary checks the order of magnitude on a computer.
// The target on the phone is 300 ms for the whole answer; this only shows
// that the search itself leaves room for the rest.
func TestSearch_SpeedOnAFullLibrary(t *testing.T) {
	if testing.Short() {
		t.Skip("builds a library of 5 600 files")
	}
	c := bigLibrary(t)
	parser := query.NewParser()
	for _, sentence := range []string{
		"Trouve-moi l'image qui contient une rose.",
		"Trouve-moi le PDF où il est question d'un notaire.",
		"le PDF de ces 6 derniers mois sur la facture d'électricité",
		"toutes les vidéos de moins d'une minute",
		"les photos de l'an dernier",
	} {
		plan := parser.Parse(sentence, testNow).Plan
		var slowest time.Duration
		for range 5 {
			started := time.Now()
			_, err := c.Search(context.Background(), plan, Options{Encoder: WordEncoder{Dim: 768}, Now: testNow})
			require.NoError(t, err)
			slowest = max(slowest, time.Since(started))
		}
		t.Logf("%-62s %v", sentence, slowest)
		require.Less(t, slowest, 2*time.Second, sentence)
	}
}

func BenchmarkSearch(b *testing.B) {
	c := bigLibrary(b)
	parser := query.NewParser()
	for name, sentence := range map[string]string{
		"picture": "Trouve-moi l'image qui contient une rose.",
		"words":   "Trouve-moi le PDF où il est question d'un notaire.",
		"filters": "les photos de l'an dernier",
	} {
		plan := parser.Parse(sentence, testNow).Plan
		b.Run(name, func(b *testing.B) {
			for b.Loop() {
				if _, err := c.Search(context.Background(), plan, Options{Encoder: WordEncoder{Dim: 768}, Now: testNow}); err != nil {
					b.Fatal(err)
				}
			}
		})
	}
}
