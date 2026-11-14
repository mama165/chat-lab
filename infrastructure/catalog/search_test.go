package catalog

import (
	"chat-lab/domain/query"
	"context"
	"encoding/json"
	"os"
	"slices"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// testNow is the day the test library is searched: every relative date in the
// sentences below is computed from it.
var testNow = time.Date(2026, time.November, 14, 12, 0, 0, 0, time.UTC)

// loadLibrary reads the test library: 40 files such as a phone may hold. The
// pictures and videos are described by a caption, which the stand-in encoder
// turns into vectors.
func loadLibrary(t testing.TB) []Item {
	t.Helper()
	data, err := os.ReadFile("testdata/library.json")
	require.NoError(t, err)
	var items []Item
	require.NoError(t, json.Unmarshal(data, &items))
	for i := range items {
		WordEncoder{}.Describe(&items[i])
	}
	return items
}

func openLibrary(t testing.TB) *Catalog {
	t.Helper()
	c, err := Open("")
	require.NoError(t, err)
	t.Cleanup(func() { c.Close() })
	require.NoError(t, c.Put(loadLibrary(t)...))
	return c
}

// ask goes all the way: the sentence is translated by the rules, then the
// plan is run on the library.
func ask(t testing.TB, c *Catalog, sentence string) Results {
	t.Helper()
	plan := query.NewParser().Parse(sentence, testNow).Plan
	require.Empty(t, query.Validate(plan, testNow, sentence), sentence)
	results, err := c.Search(context.Background(), plan, Options{Encoder: WordEncoder{}, Now: testNow, Size: 100})
	require.NoError(t, err)
	return results
}

// matches lists the files that are real matches, in order.
func matches(results Results) []string {
	var ids []string
	for _, hit := range results.Hits {
		if !hit.Beyond {
			ids = append(ids, hit.Item.ID)
		}
	}
	return ids
}

func sorted(ids ...string) []string {
	out := slices.Clone(ids)
	slices.Sort(out)
	return out
}

func TestSearch_SentencesOfTheOwner(t *testing.T) {
	c := openLibrary(t)

	t.Run("words in a document, with the page", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF où il est question d'un notaire.")
		require.Equal(t, []string{"pdf-notaire"}, matches(results))
		require.Equal(t, 3, results.Hits[0].Page)
		require.Contains(t, results.Hits[0].Excerpt, "notaire à Lyon")
		require.Equal(t, 1, results.Total)
	})

	t.Run("a period keeps the recent document only", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle.")
		require.Equal(t, []string{"pdf-notice-lv-2026"}, matches(results))
		require.Equal(t, 1, results.Hits[0].Page)
	})

	t.Run("a supplier is found under its name, the signature at the end", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF avec la signature de mon fournisseur d'électricité, à la fin.")
		require.Equal(t, "pdf-edf-contrat", results.Hits[0].Item.ID)
		require.Equal(t, 3, results.Hits[0].Page)
		require.Contains(t, results.Hits[0].Excerpt, "Signature")
	})

	t.Run("a rough size ranks, it does not filter", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi un PDF assez volumineux qui correspond à une facture d'électricité.")
		require.Equal(t, "pdf-edf-annuelle", results.Hits[0].Item.ID)
		require.Contains(t, matches(results), "pdf-edf-facture")
	})

	t.Run("a code downloaded a month ago, then all of them", func(t *testing.T) {
		sentence := "Trouve-moi le QR code que j'ai téléchargé il y a un mois, et tous ceux qui sont dans ma bibliothèque."
		parsed := query.NewParser().Parse(sentence, testNow)
		first, err := c.Search(context.Background(), parsed.Plan, Options{Encoder: WordEncoder{}, Now: testNow})
		require.NoError(t, err)
		require.Equal(t, []string{"img-qr-mois"}, matches(first))

		then, err := c.Search(context.Background(), *parsed.Then, Options{Encoder: WordEncoder{}, Now: testNow})
		require.NoError(t, err)
		require.Equal(t, []string{"img-qr-mois", "img-qr-mars", "img-qr-2025"}, matches(then))
	})

	t.Run("what a picture shows", func(t *testing.T) {
		for sentence, first := range map[string]string{
			"Trouve-moi l'image qui contient une rose.":                              "img-rose",
			"Retourne-moi les photos qui contiennent un ciel bleu.":                  "img-ciel",
			"Retrouve-moi les photos où un homme a un t-shirt bleu.":                 "img-tshirt",
			"Trouve-moi des photos où je vois apparaître un appartement.":            "img-appart",
			"Trouve-moi une vidéo dans laquelle on voit apparaître un appartement.":  "vid-appart",
			"Trouve-moi la petite vidéo d'une cuisine.":                              "vid-cuisine",
			"Trouve-moi la petite vidéo d'un paysage pris en montagne.":              "vid-montagne",
			"Trouve-moi des photos de portraits où on voit un homme avec une femme.": "img-couple",
			"Trouve-moi l'image où il y a 5 personnes.":                              "img-groupe5",
		} {
			results := ask(t, c, sentence)
			require.NotEmpty(t, results.Hits, sentence)
			require.Equal(t, first, results.Hits[0].Item.ID, sentence)
		}
	})

	t.Run("a word written in a picture is not what the picture shows", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi une image de mot herbe qui apparaît.")
		require.Equal(t, []string{"img-herbe-mot"}, matches(results))
		require.Contains(t, results.Hits[0].Excerpt, "l'herbe")
	})

	t.Run("a place keeps what cannot be ruled out, after", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi les photos prises au Mexique.")
		ids := matches(results)
		require.Equal(t, sorted("img-mexique-1", "img-mexique-2", "img-nom-mexique"), sorted(ids[:3]...))
		// Taken elsewhere, and known to be: never returned.
		require.NotContains(t, ids, "img-paris")
		require.NotContains(t, ids, "img-ville")
		// No location at all: kept, after the located ones, and marked.
		at := slices.Index(ids, "img-sans-lieu")
		require.GreaterOrEqual(t, at, 3)
		require.Equal(t, []string{"lieu inconnu"}, results.Hits[at].Notes)
		byName := slices.IndexFunc(results.Hits, func(h Hit) bool { return h.Item.ID == "img-nom-mexique" })
		require.Equal(t, []string{"lieu d'après le nom du fichier"}, results.Hits[byName].Notes)
	})

	t.Run("an exact day", func(t *testing.T) {
		require.Equal(t, []string{"vid-cuisine"}, matches(ask(t, c, "Trouve-moi la vidéo prise le 18 juin 2026.")))
	})

	t.Run("numeric limits filter", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi les PDF de moins de 2 pages.")
		for _, hit := range results.Hits {
			require.Equal(t, 1, hit.Item.PageCount, hit.Item.ID)
		}
		require.Equal(t, 9, results.Total)
		require.Equal(t, []string{"vid-concert"}, matches(ask(t, c, "Trouve-moi les vidéos de plus de 5 minutes.")))
	})

	t.Run("what is not wanted is removed when it can be told", func(t *testing.T) {
		results := ask(t, c, "Les photos de la ville, pas celles de la plage.")
		require.Equal(t, "img-ville", results.Hits[0].Item.ID)
		for _, hit := range results.Hits {
			require.NotEqual(t, "img-plage", hit.Item.ID)
			require.NotEqual(t, "img-mexique-1", hit.Item.ID) // palm trees and a beach
		}
	})

	t.Run("all the invoices of a shop", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi toutes les factures IKEA présentes dans la bibliothèque.")
		require.Equal(t, sorted("pdf-ikea-1", "pdf-ikea-2"), sorted(matches(results)[:2]...))
	})

	t.Run("an abbreviation finds the full wording", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF des CGV de mon contrat avec mon fournisseur de gaz.")
		require.Equal(t, "pdf-gaz-cgv", results.Hits[0].Item.ID)
		require.Contains(t, results.Hits[0].Excerpt, "Conditions Générales de Vente")
	})

	t.Run("a recording by its date", func(t *testing.T) {
		require.Equal(t, []string{"aud-memo"}, matches(ask(t, c, "Trouve-moi le mémo vocal que j'ai fait avant-hier.")))
	})
}

func TestSearch_OrderAndNumber(t *testing.T) {
	c := openLibrary(t)

	results := ask(t, c, "le dernier relevé de compte")
	require.Equal(t, []string{"pdf-releve-oct", "pdf-releve-sep"}, matches(results))

	results = ask(t, c, "les trois dernières vidéos")
	require.Equal(t, []string{"vid-appart", "vid-concert", "vid-montagne"}, matches(results))
	require.Equal(t, 3, results.Total)

	results = ask(t, c, "Montre les vidéos, les plus longues d'abord.")
	require.Equal(t, []string{"vid-concert", "vid-montagne", "vid-cuisine", "vid-appart"}, matches(results))

	results = ask(t, c, "le PDF le plus lourd")
	require.Equal(t, "pdf-edf-annuelle", results.Hits[0].Item.ID)
}

func TestSearch_Language(t *testing.T) {
	c := openLibrary(t)

	results := ask(t, c, "les PDF en anglais")

	ids := matches(results)
	require.Equal(t, []string{"pdf-english", "pdf-scan"}, ids)
	require.Empty(t, results.Hits[0].Notes)
	// A document whose language is not known cannot be ruled out.
	require.Equal(t, []string{"langue inconnue"}, results.Hits[1].Notes)
}

func TestSearch_LoosensFiltersWhenNothingMatches(t *testing.T) {
	c := openLibrary(t)

	results := ask(t, c, "le PDF téléchargé hier sur les impôts")

	require.Equal(t, []string{"pdf-impots"}, matches(results))
	steps := make([]query.RelaxStep, len(results.Relaxed))
	for i, r := range results.Relaxed {
		steps[i] = r.Step
	}
	// Downloaded, but more than a year ago: the period had to go, not the source.
	require.Equal(t, []query.RelaxStep{query.RelaxSource, query.RelaxDateWidened, query.RelaxDateDropped}, steps)
	require.Nil(t, results.Plan.Date)
	// What is searched is never given up: nothing about taxes that is not a PDF.
	require.Equal(t, []query.Kind{query.KindPDF}, results.Plan.Kinds)

	nothing := ask(t, c, "le PDF sur les licornes")
	require.Zero(t, nothing.Total)
	require.Empty(t, nothing.Hits)
}

func TestSearch_Conversation(t *testing.T) {
	c := openLibrary(t)
	session := query.NewSession(query.NewParser())
	run := func(sentence string) Results {
		turn := session.Interpret(sentence, testNow)
		results, err := c.Search(context.Background(), turn.Plan, Options{Encoder: WordEncoder{}, Now: testNow})
		require.NoError(t, err)
		return results
	}

	require.Equal(t, "img-cuisine", run("trouve-moi une photo de cuisine").Hits[0].Item.ID)
	require.Equal(t, "vid-cuisine", run("et en vidéo ?").Hits[0].Item.ID)
	require.Equal(t, "vid-appart", run("plutôt un appartement").Hits[0].Item.ID)
	// The only video of a flat is from this year: excluding the year leaves
	// nothing, so the exclusion is lifted and the answer says so.
	again := run("pas celle de cette année")
	require.Equal(t, "vid-appart", again.Hits[0].Item.ID)
	require.Equal(t, query.RelaxExcludedDate, again.Relaxed[0].Step)
}

func TestSearch_WithoutAVisionModel(t *testing.T) {
	c := openLibrary(t)
	plan := query.NewParser().Parse("trouve-moi une photo de cuisine", testNow).Plan

	results, err := c.Search(context.Background(), plan, Options{Now: testNow})

	require.NoError(t, err)
	require.True(t, results.VisualSkipped)
	// The description is still searched as words: here, in the file name.
	require.Equal(t, []string{"img-cuisine"}, matches(results))
}

func TestSearch_PicturesBeyondTheMatchesAreShownButNotCounted(t *testing.T) {
	c := openLibrary(t)

	results := ask(t, c, "Trouve-moi l'image qui contient une rose.")

	require.Equal(t, 1, results.Total)
	require.Equal(t, []string{"img-rose"}, matches(results))
	// The other pictures follow, marked: nothing is silently cut.
	require.Greater(t, len(results.Hits), 1)
	for _, hit := range results.Hits[1:] {
		require.True(t, hit.Beyond, hit.Item.ID)
	}

	// Sorted by date, only real matches make sense.
	sortedByDate := ask(t, c, "la photo de rose la plus récente")
	require.Equal(t, []string{"img-rose"}, matches(sortedByDate))
	require.Len(t, sortedByDate.Hits, 1)
}

func TestSearch_Pages(t *testing.T) {
	c := openLibrary(t)
	plan := query.NewParser().Parse("toutes les photos", testNow).Plan
	page := func(offset, size int) Results {
		results, err := c.Search(context.Background(), plan, Options{Now: testNow, Offset: offset, Size: size})
		require.NoError(t, err)
		return results
	}

	first, second, past := page(0, 5), page(5, 5), page(1000, 5)

	require.Equal(t, 21, first.Total)
	require.Len(t, first.Hits, 5)
	require.Len(t, second.Hits, 5)
	require.Empty(t, past.Hits)
	require.Equal(t, 21, past.Total)
	// Newest first when nothing ranks them, and no file on two pages.
	require.Equal(t, "img-facture", first.Hits[0].Item.ID)
	require.NotContains(t, matches(first), second.Hits[0].Item.ID)
	require.Len(t, page(0, 0).Hits, 20) // default page size
}

func TestSearch_AccentsAndPlurals(t *testing.T) {
	c := openLibrary(t)
	for _, sentence := range []string{
		"le pdf de la facture d'electricite de septembre",
		"LE PDF DES FACTURES D'ÉLECTRICITÉ",
	} {
		results := ask(t, c, sentence)
		require.Contains(t, matches(results), "pdf-edf-facture", sentence)
		require.Contains(t, results.Hits[0].Excerpt, "lectricité", sentence)
	}
}

func TestExcerpt(t *testing.T) {
	text := &query.TextQuery{Terms: []query.Term{{Value: "électricité", Alternatives: []string{"edf"}}}}
	long := "Début du document. " + string(make([]rune, 0)) +
		"Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore. " +
		"Votre contrat d'ÉLECTRICITÉ   prend effet\nle premier du mois. " +
		"Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris nisi ut aliquip ex ea commodo consequat."

	got := excerpt(long, text)

	require.Contains(t, got, "contrat d'ÉLECTRICITÉ prend effet le premier")
	require.True(t, len([]rune(got)) <= 2*excerptRadius+2)
	require.Equal(t, "…", string([]rune(got)[0]))

	require.Equal(t, "court", excerpt("court", text))
	require.Empty(t, excerpt("   ", text))
}
