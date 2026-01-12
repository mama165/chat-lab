package ai

import (
	"hash/fnv"
	"strings"
)

// Features transforms a raw string into a fixed-size numerical vector.
// It uses the "Hashing Trick" (Feature Hashing) to map words to vector indices,
// ensuring a consistent input format for the model without storing a vocabulary.
func Features(text string) []float64 {
	const size = 1000
	vec := make([]float64, size)

	// 1. Basic preprocessing: lowercasing and punctuation removal.
	// This must remain strictly synchronized with the Python vectorizer logic.
	t := strings.ToLower(text)
	t = strings.ReplaceAll(t, "!", "")
	t = strings.ReplaceAll(t, "?", "")
	t = strings.ReplaceAll(t, ",", "")

	// 2. Tokenization and hashing.
	// Each word is hashed using FNV-1a to determine its index in the feature vector.
	words := strings.Fields(t)
	for _, w := range words {
		h := fnv.New32a()
		h.Write([]byte(w))
		idx := int(h.Sum32()) % size
		vec[idx] += 1.0
	}
	return vec
}
