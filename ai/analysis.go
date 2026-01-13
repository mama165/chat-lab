package ai

// VectorSize defines the number of features the model expects.
// This MUST match the 'size' parameter in the Python training script.
const (
	VectorSize = 4096
	Threshold  = 0.45
)

// Analysis provides a central point for message moderation using AI.
type Analysis struct {
	vectorizer *Vectorizer
}

// NewAnalysis initializes the moderation system with a 4096-dimension vectorizer.
func NewAnalysis() *Analysis {
	return &Analysis{
		vectorizer: NewVectorizer(VectorSize),
	}
}

// CheckMessage analyzes the content and returns the toxicity score and a boolean flag.
func (a *Analysis) CheckMessage(content string) (float64, bool) {
	// 1. Transform raw text into a numerical slice ([]float64)
	vec := a.vectorizer.Features(content)

	// 2. PredictToxicity returns a []float64 (probabilities for each class).
	// Typically: index 0 is "Sane", index 1 is "Toxic".
	predictions := PredictToxicity(vec)

	// 3. Extract the toxic score (index 1)
	// We check the length first to avoid a panic, just in case.
	var score float64
	if len(predictions) > 1 {
		score = predictions[1]
	} else if len(predictions) == 1 {
		score = predictions[0]
	}

	// 4. Robustness threshold.
	isToxic := score > Threshold

	return score, isToxic
}
