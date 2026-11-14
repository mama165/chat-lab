package catalog

import (
	"chat-lab/domain/query"
	"context"
	"hash/fnv"
	"strings"
)

// WordEncoder is a stand-in for a vision model, for tests and demonstrations.
//
// It does not look at pictures: it turns words into a vector, so that a
// description is close to a caption sharing its words. It lets the whole
// search be exercised on a computer, with entries whose content is described
// by a caption. It says nothing about how well a real model understands
// pictures; that can only be measured with the model.
type WordEncoder struct {
	// Dim is the size of the vectors; 256 when zero.
	Dim int
}

var encoderStopWords = map[string]struct{}{
	"un": {}, "une": {}, "des": {}, "le": {}, "la": {}, "les": {}, "de": {}, "du": {}, "d": {}, "l": {},
	"a": {}, "au": {}, "aux": {}, "en": {}, "et": {}, "avec": {}, "dans": {}, "sur": {}, "qui": {}, "dessus": {},
}

// EncodeText implements TextEncoder.
func (e WordEncoder) EncodeText(_ context.Context, text string) ([]float32, error) {
	dim := e.Dim
	if dim <= 0 {
		dim = 256
	}
	vector := make([]float32, dim)
	for _, word := range strings.Fields(query.Fold(text)) {
		if _, stop := encoderStopWords[word]; stop {
			continue
		}
		// A plural counts as its singular.
		if len(word) > 3 {
			word = strings.TrimRight(word, "sx")
		}
		h := fnv.New32a()
		h.Write([]byte(word))
		sum := h.Sum32()
		sign := float32(1)
		if sum&1 == 1 {
			sign = -1
		}
		vector[int(sum>>1)%dim] += sign
	}
	return vector, nil
}

// Describe gives an entry the vectors of its caption, when it has none: one
// vector per part of the caption separated by "|", as a video has one per
// sampled frame.
func (e WordEncoder) Describe(item *Item) {
	if len(item.Vectors) > 0 || item.Caption == "" {
		return
	}
	for _, part := range strings.Split(item.Caption, "|") {
		if vector, err := e.EncodeText(context.Background(), part); err == nil {
			item.Vectors = append(item.Vectors, vector)
		}
	}
}
