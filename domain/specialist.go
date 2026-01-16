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
	Version          string
	ProcessingTimeMs int
	Status           string
}

type SpecialistConfig struct {
	ID      SpecialistID
	BinPath string
	Port    int
}

type AppConfig struct {
	Specialists []SpecialistConfig
}
