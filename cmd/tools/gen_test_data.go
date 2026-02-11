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
	// Dossier de destination pour le Scanner
	outputDir := "./test_data"
	err := os.MkdirAll(outputDir, 0755)
	if err != nil {
		panic(fmt.Sprintf("Impossible de créer le dossier : %v", err))
	}

	fmt.Println("🚀 Chat-Lab : Génération des fichiers de test...")

	// 1. Génération d'un vrai PDF (pour pdf_specialist.py)
	// Nécessite : go get github.com/jung-kurt/gofpdf
	pdfPath := filepath.Join(outputDir, "rapport_test.pdf")
	genPDF(pdfPath)

	// 2. Génération d'une image PNG (pour image_specialist.py)
	imgPath := filepath.Join(outputDir, "capture_test.png")
	genImage(imgPath)

	// 3. Intégration de ton fichier macOS .aiff (pour audio_specialist.py)
	// REMPLACE par le nom de ton fichier actuel à la racine
	aiffSource := "sample_audio_macos.aiff"
	aiffDest := filepath.Join(outputDir, "audio_macos_test.aiff")

	if err := prepareAudio(aiffSource, aiffDest); err != nil {
		fmt.Printf("⚠️  Audio : %v (Place un fichier %s à côté du projet pour tester)\n", err, aiffSource)
	}

	fmt.Println("\n✅ Prêt ! Tu peux maintenant lancer le Scanner sur ./test_data")
}

// genPDF crée un document multi-pages pour tester l'extraction de texte
func genPDF(path string) {
	pdf := gofpdf.New("P", "mm", "A4", "")
	pdf.AddPage()
	pdf.SetFont("Arial", "B", 20)
	pdf.Cell(40, 20, "Chat-Lab : Analyse PDF")
	pdf.Ln(20)

	pdf.SetFont("Arial", "", 12)
	content := "Ceci est un document généré pour tester le spécialiste Python.\n" +
		"Le moteur PyMuPDF devrait extraire ce texte et compter 1 page."
	pdf.MultiCell(0, 10, content, "", "", false)

	err := pdf.OutputFileAndClose(path)
	if err != nil {
		fmt.Printf("❌ Erreur PDF : %v\n", err)
	} else {
		fmt.Printf("📄 PDF généré : %s\n", path)
	}
}

// genImage crée un PNG de 800x600 pour tester PIL (Pillow)
func genImage(path string) {
	width, height := 800, 600
	img := image.NewRGBA(image.Rectangle{image.Point{0, 0}, image.Point{width, height}})

	// Remplissage avec un dégradé bleu pour le style
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

// prepareAudio copie ton fichier AIFF réel vers le dossier de test
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
