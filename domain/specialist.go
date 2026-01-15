package domain

type SpecialistID string

const (
	TOXICITY  SpecialistID = "Toxicity"
	SENTIMENT SpecialistID = "Sentiment"
	BUSINESS  SpecialistID = "Business"
)

type SpecialistRequest struct {
	MessageID string
	Content   string
	Tags      map[string]string
}

type SpecialistResponse struct {
	Score            float64
	Label            string
	version          string
	ProcessingTimeMs int
	Status           string
}
