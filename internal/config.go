package internal

import (
	"fmt"
	"time"
)

type Config struct {
	BufferSize           int           `env:"BUFFER_SIZE,required=true"`
	ConnectionBufferSize int           `env:"CONNECTION_BUFFER_SIZE,required=true"`
	NumberOfWorkers      int           `env:"NUMBER_OF_WORKERS,required=true"`
	CharReplacement      string        `env:"CHARACTER_REPLACEMENT,required=true"`
	LimitMessages        *int          `env:"LIMIT_MESSAGES"`
	SinkTimeout          time.Duration `env:"SINK_TIMEOUT,required=true"`
	MetricInterval       time.Duration `env:"METRIC_INTERVAL,required=true"`
	IngestionTimeout     time.Duration `env:"INGESTION_TIMEOUT,required=true"`

	BufferTimeout     time.Duration `env:"BUFFER_TIMEOUT,required=true"`
	SpecialistTimeout time.Duration `env:"SPECIALIST_TIMEOUT,required=true"`

	LatencyThreshold                 time.Duration `env:"LATENCY_THRESHOLD,required=true"`
	RestartInterval                  time.Duration `env:"RESTART_INTERVAL,required=true"`
	AuthTokenDuration                time.Duration `env:"AUTH_TOKEN_DURATION,required=true"`
	BadgerFilepath                   string        `env:"BADGER_FILEPATH,required=true"`
	BlugeFilepath                    string        `env:"BLUGE_FILEPATH,required=true"`
	LogLevel                         string        `env:"LOG_LEVEL,required=true"`
	LowCapacityThreshold             int           `env:"LOW_CAPACITY_THRESHOLD,required=true"`
	MaxContentLength                 int           `env:"MAX_CONTENT_LENGTH,required=true"`
	MinScoring                       float64       `env:"MIN_SCORING,required=true"`
	MaxScoring                       float64       `env:"MAX_SCORING,required=true"`
	MaxAnalyzedEvent                 int           `env:"MAX_ANALYZED_EVENT,required=true"`
	Host                             string        `env:"HOST,required=true"`
	Port                             int           `env:"PORT,required=true"`
	MaxSpecialistBootDuration        time.Duration `env:"MAX_SPECIALIST_BOOT_DURATION,required=true"`
	ToxicityBinPath                  string        `env:"TOXICITY_BIN_PATH,required=true"`
	ToxicityPort                     int           `env:"TOXICITY_PORT,required=true"`
	SentimentBinPath                 string        `env:"SENTIMENT_BIN_PATH,required=true"`
	SentimentPort                    int           `env:"SENTIMENT_PORT,required=true"`
	EnableSpecialists                bool          `env:"ENABLE_SPECIALISTS,required=true"`
	RootDir                          string        `env:"ROOT_DIR,required=true"`
	ScannerWorkerNb                  int           `env:"SCANNER_WORKER_NB,required=true"`
	DriveID                          string        `env:"DRIVE_ID,required=true"`
	ScannerBackpressureLowThreshold  int           `env:"SCANNER_BACKPRESSURE_LOW_THRESHOLD_PERCENT,required=true"`
	ScannerBackpressureHardThreshold int           `env:"SCANNER_BACKPRESSURE_HARD_THRESHOLD_PERCENT,required=true"`
}

func CharacterRune(str string) (rune, error) {
	r := []rune(str)
	if len(r) != 1 {
		return 0, fmt.Errorf(
			"CHARACTER_REPLACEMENT must be a single character, got %q",
			str,
		)
	}
	return r[0], nil
}
