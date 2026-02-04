package domain

import "chat-lab/domain/mimetypes"

type Metric string

const (
	MetricToxicity  Metric = "toxicity"
	MetricSentiment Metric = "sentiment"
	MetricBusiness  Metric = "business"
	MetricPDF       Metric = "pdf"
	MetricAudio     Metric = "audio"
	MetricImage     Metric = "image"
)

type Config struct {
	ID           Metric
	BinPath      string
	Host         string
	Port         int
	Capabilities []mimetypes.MIME // ex: ["application/pdf", "audio/mpeg", "video/mp4"]
}

func NewConfig(ID Metric, BinPath string, Host string, Port int, Capabilities []mimetypes.MIME) Config {
	return Config{
		ID:           ID,
		BinPath:      BinPath,
		Host:         Host,
		Port:         Port,
		Capabilities: Capabilities,
	}
}
