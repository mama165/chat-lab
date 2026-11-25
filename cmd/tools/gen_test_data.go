package main

import (
	"fmt"
	"image"
	"image/color"
	"image/png"
	"io"
	"os"
	"path/filepath"

	"github.com/jung-kurt/gofpdf"
)

func main() {
	// Destination folder for the Scanner
	outputDir := "./test_data"
	err := os.MkdirAll(outputDir, 0755)
	if err != nil {
		panic(fmt.Sprintf("Impossible de créer le dossier : %v", err))
	}

	fmt.Println("🚀 Chat-Lab : Génération des fichiers de test...")

	// 1. Generate a real PDF (for pdf_specialist.py)
	// Nécessite : go get github.com/jung-kurt/gofpdf
	pdfPath := filepath.Join(outputDir, "rapport_test.pdf")
	genPDF(pdfPath)

	// 2. Generate a PNG image (for image_specialist.py)
	imgPath := filepath.Join(outputDir, "capture_test.png")
	genImage(imgPath)

	// 3. Integrate your macOS .aiff file (for audio_specialist.py)
	// REPLACE with the name of your current file at the root
	aiffSource := "sample_audio_macos.aiff"
	aiffDest := filepath.Join(outputDir, "audio_macos_test.aiff")

	if err := prepareAudio(aiffSource, aiffDest); err != nil {
		fmt.Printf("⚠️  Audio : %v (Place un fichier %s à côté du projet pour tester)\n", err, aiffSource)
	}

	fmt.Println("\n✅ Prêt ! Tu peux maintenant lancer le Scanner sur ./test_data")
}

// genPDF creates a multi-page document to test text extraction
func genPDF(path string) {
	pdf := gofpdf.New("P", "mm", "A4", "")
	// The built-in fonts only know Windows-1252: without this conversion, the UTF-8
	// bytes of an accented letter are drawn as two wrong characters ("é" -> "Ã©").
	translate := pdf.UnicodeTranslatorFromDescriptor("")
	pdf.AddPage()
	pdf.SetFont("Arial", "B", 20)
	pdf.Cell(40, 20, translate("Chat-Lab : Analyse PDF"))
	pdf.Ln(20)

	pdf.SetFont("Arial", "", 12)
	content := "Ceci est un document généré pour tester le spécialiste Python.\n" +
		"Le moteur PyMuPDF devrait extraire ce texte et compter 1 page."
	pdf.MultiCell(0, 10, translate(content), "", "", false)

	err := pdf.OutputFileAndClose(path)
	if err != nil {
		fmt.Printf("❌ Erreur PDF : %v\n", err)
	} else {
		fmt.Printf("📄 PDF généré : %s\n", path)
	}
}

// genImage creates an 800x600 PNG to test PIL (Pillow)
func genImage(path string) {
	width, height := 800, 600
	img := image.NewRGBA(image.Rectangle{image.Point{0, 0}, image.Point{width, height}})

	// Fill with a blue gradient for the style
	for x := 0; x < width; x++ {
		for y := 0; y < height; y++ {
			c := color.RGBA{uint8(x % 255), 100, 200, 0xff}
			img.Set(x, y, c)
		}
	}

	f, _ := os.Create(path)
	defer f.Close()
	if err := png.Encode(f, img); err != nil {
		fmt.Printf("❌ Erreur Image : %v\n", err)
	} else {
		fmt.Printf("📸 Image générée : %s\n", path)
	}
}

// prepareAudio copies your real AIFF file to the test folder
func prepareAudio(src, dst string) error {
	source, err := os.Open(src)
	if err != nil {
		return fmt.Errorf("fichier source introuvable")
	}
	defer source.Close()

	destination, err := os.Create(dst)
	if err != nil {
		return err
	}
	defer destination.Close()

	_, err = io.Copy(destination, source)
	if err == nil {
		fmt.Printf("🎙️  Audio AIFF copié : %s\n", dst)
	}
	return err
}
