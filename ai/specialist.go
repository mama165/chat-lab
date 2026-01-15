package ai

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
