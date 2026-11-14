// Package carnet runs the whole path on a real folder of files: they are
// written to disk, read by the catalog, searched with sentences, and the
// answers are checked against what was written.
//
// It needs the poppler tools (pdftotext, pdfinfo) to read the pages of PDF
// documents. Without them the test is skipped, unless REQUIRE_POPPLER=1 is set,
// as the CI does, in which case it fails.
//
// What it does not cover: the pictures are described by captions written by
// hand (the vision model does not run here), and the place of a picture is not
// read from its metadata yet.
package carnet

import (
	"chat-lab/domain/query"
	"chat-lab/infrastructure/catalog"
	"context"
	"image"
	"image/color"
	"image/png"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/jung-kurt/gofpdf"
	"github.com/stretchr/testify/require"
)

// today is the day the sentences are read on; relative dates are computed from it.
var today = time.Date(2026, time.November, 14, 12, 0, 0, 0, time.UTC)

func requirePoppler(t *testing.T) {
	t.Helper()
	for _, tool := range []string{"pdftotext", "pdfinfo"} {
		if _, err := exec.LookPath(tool); err != nil {
			if os.Getenv("REQUIRE_POPPLER") == "1" {
				t.Fatalf("%s is required (REQUIRE_POPPLER=1)", tool)
			}
			t.Skipf("%s is not installed", tool)
		}
	}
}

// writePDF writes a document with one text per page.
func writePDF(t *testing.T, path string, pages ...string) {
	t.Helper()
	pdf := gofpdf.New("P", "mm", "A4", "")
	pdf.SetFont("Helvetica", "", 12)
	// The text is UTF-8: it is translated to the encoding of the core fonts.
	translate := pdf.UnicodeTranslatorFromDescriptor("")
	for _, text := range pages {
		pdf.AddPage()
		pdf.MultiCell(0, 7, translate(text), "", "L", false)
	}
	require.NoError(t, pdf.OutputFileAndClose(path))
}

// writePicture writes a plain picture; its content is not what the test looks
// at, only that it is a valid picture.
func writePicture(t *testing.T, path string, shade uint8) {
	t.Helper()
	img := image.NewRGBA(image.Rect(0, 0, 64, 48))
	for x := range 64 {
		for y := range 48 {
			img.Set(x, y, color.RGBA{shade, 128, 255 - shade, 255})
		}
	}
	file, err := os.Create(path)
	require.NoError(t, err)
	defer file.Close()
	require.NoError(t, png.Encode(file, img))
}

func setTime(t *testing.T, path string, when time.Time) {
	t.Helper()
	require.NoError(t, os.Chtimes(path, when, when))
}

// makePhone writes a folder shaped like a phone's storage.
func makePhone(t *testing.T) (root string, captions map[string]string) {
	t.Helper()
	root = t.TempDir()
	for _, dir := range []string{"DCIM/Camera", "Download", "Documents", "WhatsApp"} {
		require.NoError(t, os.MkdirAll(filepath.Join(root, dir), 0o755))
	}
	at := func(day int) time.Time { return time.Date(2026, time.November, day, 9, 0, 0, 0, time.UTC) }

	notaire := filepath.Join(root, "Download", "acte-vente.pdf")
	writePDF(t, notaire,
		"Compromis de vente d'un appartement situé à Lyon.",
		"Désignation du bien et diagnostics techniques.",
		"Maître Dupont, notaire à Lyon, atteste que l'acte a été reçu en son étude.")
	setTime(t, notaire, at(2)) // about a year ago in the story below

	edf := filepath.Join(root, "Documents", "contrat-fourniture.pdf")
	writePDF(t, edf,
		"EDF - Contrat de fourniture d'électricité. Conditions particulières.",
		"Grille tarifaire, prix du kWh et abonnement.",
		"Fait à Paris en deux exemplaires. Signature du client. Signature pour EDF.")
	setTime(t, edf, at(3))

	facture := filepath.Join(root, "Documents", "facture-edf-septembre.pdf")
	writePDF(t, facture, "EDF Facture d'électricité de septembre 2026. Montant à payer : 84,20 euros.")
	setTime(t, facture, at(5))

	notice := filepath.Join(root, "Download", "notice-bosch-sms4.pdf")
	writePDF(t, notice,
		"Notice d'utilisation du lave-vaisselle Bosch SMS4. Consignes de sécurité.",
		"Programme éco, sel régénérant et liquide de rinçage.")
	setTime(t, notice, at(10))

	ikea := filepath.Join(root, "Download", "IKEA_ticket.pdf")
	writePDF(t, ikea, "IKEA France. Ticket de caisse valant facture. Étagère et luminaire.")
	setTime(t, ikea, at(12))

	writePicture(t, filepath.Join(root, "DCIM", "Camera", "IMG_0101.png"), 200)
	setTime(t, filepath.Join(root, "DCIM", "Camera", "IMG_0101.png"), at(13))
	writePicture(t, filepath.Join(root, "DCIM", "Camera", "IMG_0102.png"), 40)
	setTime(t, filepath.Join(root, "DCIM", "Camera", "IMG_0102.png"), at(13))
	writePicture(t, filepath.Join(root, "WhatsApp", "IMG-chat.png"), 90)
	setTime(t, filepath.Join(root, "WhatsApp", "IMG-chat.png"), at(14))

	// Not a library file: ignored without a word.
	require.NoError(t, os.WriteFile(filepath.Join(root, "Documents", "notes.txt"), []byte("rien"), 0o644))

	captions = map[string]string{
		"DCIM/Camera/IMG_0101.png": "une rose rouge dans un jardin",
		"DCIM/Camera/IMG_0102.png": "un ciel bleu au dessus de la mer",
		"WhatsApp/IMG-chat.png":    "un chat qui dort sur un canapé",
	}
	return root, captions
}

// ask runs one sentence on the catalog and returns the answer as lines of
// file names, page numbers and excerpts, for the checks.
func ask(t *testing.T, c *catalog.Catalog, sentence string, captionsUsed bool) catalog.Results {
	t.Helper()
	plan := query.NewParser().Parse(sentence, today).Plan
	require.Empty(t, query.Validate(plan, today, sentence), sentence)
	opts := catalog.Options{Now: today, Size: 50}
	if captionsUsed {
		opts.Encoder = catalog.WordEncoder{}
	}
	results, err := c.Search(context.Background(), plan, opts)
	require.NoError(t, err, sentence)
	return results
}

func names(results catalog.Results) []string {
	var out []string
	for _, hit := range results.Hits {
		if !hit.Beyond {
			out = append(out, hit.Item.Name)
		}
	}
	return out
}

func TestCarnet_IngestSearchAndRestart(t *testing.T) {
	requirePoppler(t)
	root, captions := makePhone(t)
	store := t.TempDir()
	ctx := context.Background()
	options := catalog.IngestOptions{PDF: catalog.PopplerReader{}, Captions: captions}

	// First pass: everything is new.
	c, err := catalog.Open(store)
	require.NoError(t, err)
	report, err := catalog.Ingest(ctx, c, root, options)
	require.NoError(t, err)
	require.Equal(t, 8, report.Added, "%+v", report) // 5 documents and 3 pictures
	require.Empty(t, report.Skipped, "%+v", report)
	require.Equal(t, 0, report.Removed)
	require.Equal(t, 8, c.Len())

	t.Run("a question about a document reads its pages", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF où il est question d'un notaire.", true)
		require.Equal(t, []string{"acte-vente.pdf"}, names(results))
		require.Equal(t, 3, results.Hits[0].Page)
		require.Contains(t, results.Hits[0].Excerpt, "notaire à Lyon")
	})

	t.Run("the supplier's signature at the end of the contract", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF avec la signature de mon fournisseur d'électricité, à la fin.", true)
		require.Equal(t, "contrat-fourniture.pdf", results.Hits[0].Item.Name)
		require.Equal(t, 3, results.Hits[0].Page)
	})

	t.Run("a bill of a month, by its words", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi la facture d'électricité de septembre.", true)
		require.Equal(t, "facture-edf-septembre.pdf", results.Hits[0].Item.Name)
		require.Contains(t, results.Hits[0].Excerpt, "84,20")
	})

	t.Run("a period keeps what was added in it", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi les documents de ces 6 derniers mois sur le lave-vaisselle.", true)
		require.Equal(t, []string{"notice-bosch-sms4.pdf"}, names(results))
	})

	t.Run("a picture, by what it shows", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi l'image qui contient une rose.", true)
		require.Equal(t, "IMG_0101.png", results.Hits[0].Item.Name)
		results = ask(t, c, "Montre-moi les photos où on voit un ciel bleu.", true)
		require.Equal(t, "IMG_0102.png", results.Hits[0].Item.Name)
	})

	t.Run("a picture is found by its folder and name too", func(t *testing.T) {
		results := ask(t, c, "les photos du WhatsApp", false)
		require.Equal(t, []string{"IMG-chat.png"}, names(results))
	})

	t.Run("several words, all of them in the file", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi les PDF avec les mots ikea et étagère.", false)
		require.Equal(t, []string{"IKEA_ticket.pdf"}, names(results))
	})

	t.Run("nothing matches: nothing is invented", func(t *testing.T) {
		results := ask(t, c, "Trouve-moi le PDF sur les licornes.", false)
		require.Zero(t, results.Total)
		require.Empty(t, results.Hits)
	})

	require.NoError(t, c.Close())

	t.Run("second pass: nothing is read again", func(t *testing.T) {
		c, err := catalog.Open(store)
		require.NoError(t, err)
		defer c.Close()
		require.Equal(t, 8, c.Len(), "the library survived the restart")
		report, err := catalog.Ingest(ctx, c, root, options)
		require.NoError(t, err)
		require.Equal(t, catalog.Report{Unchanged: 8}, report)
	})

	t.Run("a changed and a removed file", func(t *testing.T) {
		// The bill was replaced by a new version, and the old notice deleted.
		facture := filepath.Join(root, "Documents", "facture-edf-septembre.pdf")
		writePDF(t, facture, "EDF Facture d'électricité d'octobre 2026. Montant à payer : 91,10 euros.")
		setTime(t, facture, time.Date(2026, time.November, 6, 9, 0, 0, 0, time.UTC))
		require.NoError(t, os.Remove(filepath.Join(root, "Download", "notice-bosch-sms4.pdf")))

		c, err := catalog.Open(store)
		require.NoError(t, err)
		defer c.Close()
		report, err := catalog.Ingest(ctx, c, root, options)
		require.NoError(t, err)
		require.Equal(t, catalog.Report{Unchanged: 6, Updated: 1, Removed: 1}, report)

		results := ask(t, c, "Trouve-moi la facture d'électricité.", false)
		require.Equal(t, "facture-edf-septembre.pdf", results.Hits[0].Item.Name)
		require.Contains(t, results.Hits[0].Excerpt, "91,10")
		require.Contains(t, results.Hits[0].Excerpt, "octobre")
		// The words of the removed notice are gone with it.
		require.Zero(t, ask(t, c, "Trouve-moi le lave-vaisselle Bosch.", false).Total)
	})
}

func TestCarnet_TheWholeCatalogAnswersLikeAPhone(t *testing.T) {
	requirePoppler(t)
	root, captions := makePhone(t)
	c, err := catalog.Open("")
	require.NoError(t, err)
	defer c.Close()
	_, err = catalog.Ingest(context.Background(), c, root, catalog.IngestOptions{PDF: catalog.PopplerReader{}, Captions: captions})
	require.NoError(t, err)

	// A general question: everything of the library, as the phone would list it.
	results := ask(t, c, "Montre-moi tous les fichiers de la bibliothèque.", false)
	require.Equal(t, 8, results.Total)

	counts := c.Counts()
	require.Equal(t, 3, counts[query.KindImage])
	require.Equal(t, 5, counts[query.KindPDF])

	// The report of a question the owner would really ask.
	for _, sentence := range []string{
		"Trouve-moi le PDF où il est question d'un notaire.",
		"Trouve-moi l'image qui contient une rose.",
	} {
		hit := ask(t, c, sentence, true).Hits[0]
		t.Logf("%s\n  %s, page %d : %s", sentence, hit.Item.Name, hit.Page, hit.Excerpt)
	}
}
