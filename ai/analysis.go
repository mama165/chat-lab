package ai

// IsMalicious analyzes the input text and determines if it should be flagged.
// It encapsulates the feature extraction and the model prediction logic.
func IsMalicious(text string) (bool, float64) {
	// 1. Transform the raw string into a numerical feature vector
	input := Features(text)

	// 2. Call the generated prediction logic from model.go
	score := PredictToxicity(input)

	// 3. Define the decision threshold (0.5 is the standard balance point)
	return score > 0.5, score
}
