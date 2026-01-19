package specialist

type Metric string

const (
	MetricToxicity  Metric = "toxicity"
	MetricSentiment Metric = "sentiment"
	MetricBusiness  Metric = "business"
)

type Config struct {
	ID      Metric
	BinPath string
	Host    string
	Port    int
}
