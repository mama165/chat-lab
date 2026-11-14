package query

import (
	"fmt"
	"strings"
)

// Chip is one criterion of a plan, worded for the user. Chips are shown above
// the results: a misunderstanding is visible at once and fixed with one tap,
// instead of being discovered by scrolling through wrong results.
type Chip struct {
	// Field is the part of the plan the chip stands for.
	Field string `json:"field"`
	Label string `json:"label"`
}

var kindLabels = map[Kind]string{
	KindImage: "photos", KindVideo: "vidéos", KindPDF: "PDF", KindAudio: "audio",
}

// Chips words every criterion of the plan, in French.
func (p Plan) Chips() []Chip {
	var chips []Chip
	add := func(field, label string) { chips = append(chips, Chip{Field: field, Label: label}) }

	for _, k := range p.Kinds {
		label, ok := kindLabels[k]
		if !ok {
			label = string(k)
		}
		add("kinds", label)
	}
	if p.Source != nil {
		switch p.Source.Source {
		case SourceDownload:
			add("source", "téléchargé")
		case SourceScreenshot:
			add("source", "capture d'écran")
		case SourceMessaging:
			add("source", "reçu par messagerie")
		}
	}
	if p.Date != nil {
		add("date", p.Date.Label)
	}
	for _, d := range p.ExcludeDates {
		add("exclude_dates", "sauf "+d.Label)
	}
	for _, f := range p.Features {
		switch f.Name {
		case FeatureCode:
			add("features", "QR code")
		case FeaturePortrait:
			add("features", "portrait")
		case FeatureSignature:
			add("features", "signature")
		}
	}
	if p.Visual != nil {
		add("visual", "image : "+p.Visual.Description)
		for _, not := range p.Visual.Not {
			add("visual", "sans "+not)
		}
	}
	if p.Text != nil && !p.Text.Weak {
		values := make([]string, len(p.Text.Terms))
		for i, term := range p.Text.Terms {
			values[i] = term.Value
		}
		prefix := "texte : "
		if p.Text.Scope == ScopeWritten {
			prefix = "mot écrit : "
		}
		add("text", prefix+strings.Join(values, ", "))
		for _, not := range p.Text.Not {
			add("text", "sans le mot "+not)
		}
	}
	if p.People != nil {
		noun := "personnes"
		if p.People.Value == 1 {
			noun = "personne"
		}
		switch {
		case p.People.AtLeast:
			add("people", fmt.Sprintf("au moins %d %s", p.People.Value, noun))
		case p.People.Tolerance > 0:
			add("people", fmt.Sprintf("environ %d %s", p.People.Value, noun))
		default:
			add("people", fmt.Sprintf("%d %s", p.People.Value, noun))
		}
	}
	if p.Size != nil {
		if p.Size.Level == LevelLarge {
			add("size", "volumineux")
		} else {
			add("size", "léger")
		}
	}
	if p.Duration != nil {
		if p.Duration.Level == LevelLarge {
			add("duration", "longue durée")
		} else {
			add("duration", "courte durée")
		}
	}
	if p.Position != nil {
		switch p.Position.Where {
		case PositionEnd:
			add("position", "à la fin")
		case PositionPage:
			add("position", fmt.Sprintf("page %d", p.Position.Page))
		default:
			add("position", "au début")
		}
	}
	switch p.Sort {
	case SortNewest:
		add("sort", "plus récents d'abord")
	case SortOldest:
		add("sort", "plus anciens d'abord")
	case SortLargest:
		add("sort", "plus gros d'abord")
	case SortSmallest:
		add("sort", "plus petits d'abord")
	}
	if p.Limit > 0 {
		add("limit", fmt.Sprintf("%d résultats", p.Limit))
	}
	if p.Cardinality == CardinalityAll {
		add("cardinality", "tous les résultats")
	}
	return chips
}

// Summary is the plan on one line: "PDF · depuis le 4 mai 2026 · texte : notice, lave-vaisselle".
func (p Plan) Summary() string {
	chips := p.Chips()
	labels := make([]string, len(chips))
	for i, c := range chips {
		labels[i] = c.Label
	}
	return strings.Join(labels, " · ")
}
