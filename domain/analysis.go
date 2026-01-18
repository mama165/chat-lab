package domain

type DataType string

const (
	TextType  DataType = "text"
	AudioType DataType = "audio"
	FileType  DataType = "file"
)

type AnalysisMetric string

const (
	MetricToxicity  AnalysisMetric = "toxicity"
	MetricSentiment AnalysisMetric = "sentiment"
	MetricBusiness  AnalysisMetric = "business"
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
	ID      AnalysisMetric
	BinPath string
	Host    string
	Port    int
}

type AppConfig struct {
	Specialists []SpecialistConfig
}
