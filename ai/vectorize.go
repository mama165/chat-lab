package ai

import (
	"hash/fnv"
	"strings"
)

// Vectorizer provides methods to transform text into numerical features.
type Vectorizer struct {
	size int
}

// NewVectorizer initializes a vectorizer with a fixed size.
// This size must match the 'n_features' parameter in the Python script.
func NewVectorizer(size int) *Vectorizer {
	return &Vectorizer{size: size}
}

// Features transforms a raw string into a fixed-size numerical vector.
// It uses the "Hashing Trick" (Feature Hashing) to map words to vector indices.
// Features transforms a raw string into a fixed-size numerical vector.
func (v *Vectorizer) Features(text string) []float64 {
	vec := make([]float64, v.size)

	// 1. Minimal preprocessing: Just lowercase.
	// We KEEP punctuation because "f*ck" is a different signal than "fck".
	// We KEEP numbers because "1diot" is different from "idiot".
	cleanText := strings.ToLower(text)

	// 2. Tokenization and hashing.
	words := strings.Fields(cleanText)
	for _, w := range words {
		h := fnv.New32a()
		h.Write([]byte(w))
		idx := int(h.Sum32()) % v.size

		// Binary feature (1.0 if present) or count.
		// For short chat messages, 1.0 is often more robust.
		vec[idx] = 1.0
	}
	return vec
}
