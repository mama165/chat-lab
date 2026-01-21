package main

import (
	"fmt"
	"fyne.io/fyne/v2"
	"image/color"
	"time"

	"fyne.io/fyne/v2/app"
	"fyne.io/fyne/v2/canvas"
	"fyne.io/fyne/v2/container"
	"fyne.io/fyne/v2/widget"
)

// Defining Cyberpunk colors
var (
	CyanNeon   = color.NRGBA{R: 0, G: 243, B: 255, A: 255}
	PurpleNeon = color.NRGBA{R: 188, G: 19, B: 254, A: 255}
	GreenNeon  = color.NRGBA{R: 10, G: 255, B: 96, A: 255}
	RedNeon    = color.NRGBA{R: 255, G: 0, B: 60, A: 255}
)

func main() {
	myApp := app.New()
	myWindow := myApp.NewWindow("CHAT-LAB // DEEP SCAN & FORENSICS")
	myWindow.Resize(fyne.NewSize(1000, 700))

	// Title with Neon effect
	title := canvas.NewText("CHAT-LAB // SYSTEM SCANNER", CyanNeon)
	title.TextSize = 24
	title.TextStyle = fyne.TextStyle{Bold: true}

	// Metrics display
	filesCount := widget.NewLabel("FILES: 0")
	bytesCount := widget.NewLabel("DATA: 0 MB")
	statusLabel := widget.NewLabelWithStyle("LIVE SCANNING", fyne.TextAlignLeading, fyne.TextStyle{Bold: true})
	statusLabel.Hide() // Will blink later

	// Progress bar for the scan
	progress := widget.NewProgressBar()

	// Terminal simulation for file logs
	terminal := widget.NewMultiLineEntry()
	terminal.SetText(">> INIT SYSTEM...\n>> CONNECTING TO gRPC STREAM...\n")
	terminal.TextStyle = fyne.TextStyle{Monospace: true}
	terminal.Disable() // Read-only

	// AI Pipelines status
	aiWhisper := createMetricCard("WHISPER AI", "IDLE", PurpleNeon)
	aiToxicity := createMetricCard("TOXICITY", "0%", RedNeon)

	// Layout organization
	header := container.NewVBox(title, progress)
	metrics := container.NewHBox(filesCount, layoutSpacer(), bytesCount)

	mainContent := container.NewHSplit(
		container.NewVBox(widget.NewLabel("INGESTION STREAM"), terminal),
		container.NewVBox(widget.NewLabel("AI ANALYSIS"), aiWhisper, aiToxicity),
	)
	mainContent.SetOffset(0.7)

	myWindow.SetContent(container.NewBorder(header, metrics, nil, nil, mainContent))

	// Simulation loop
	go func() {
		count := 0
		for {
			count++
			filesCount.SetText(fmt.Sprintf("FILES: %d", count))
			bytesCount.SetText(fmt.Sprintf("DATA: %.2f GB", float64(count)*0.15))
			progress.SetValue(float64(count%100) / 100.0)

			// Append to terminal
			newLog := fmt.Sprintf(">> SCANNING: /sys/data/file_%d.wav\n", count)
			terminal.SetText(terminal.Text + newLog)
			// Simple autoscroll simulation (not native in Fyne's Entry, but we mock it)
			if len(terminal.Text) > 500 {
				terminal.SetText(terminal.Text[100:])
			}

			time.Sleep(time.Millisecond * 200)
		}
	}()

	myWindow.ShowAndRun()
}

func createMetricCard(name, value string, textColor color.Color) fyne.CanvasObject {
	t := canvas.NewText(name, color.White)
	v := canvas.NewText(value, textColor)
	v.TextSize = 20
	v.TextStyle = fyne.TextStyle{Bold: true}
	return container.NewVBox(t, v)
}

func layoutSpacer() fyne.CanvasObject {
	return canvas.NewRectangle(color.Transparent)
}
