package main

import (
	"fmt"
	"time"
)

type Config struct {
	BufferSize                int           `env:"BUFFER_SIZE,required=true"`
	ConnectionBufferSize      int           `env:"CONNECTION_BUFFER_SIZE,required=true"`
	NumberOfWorkers           int           `env:"NUMBER_OF_WORKERS,required=true"`
	CharReplacement           string        `env:"CHARACTER_REPLACEMENT,required=true"`
	LimitMessages             *int          `env:"LIMIT_MESSAGES"`
	SinkTimeout               time.Duration `env:"SINK_TIMEOUT,required=true"`
	MetricInterval            time.Duration `env:"METRIC_INTERVAL,required=true"`
	IngestionTimeout          time.Duration `env:"INGESTION_TIMEOUT,required=true"`
	DeliveryTimeout           time.Duration `env:"DELIVERY_TIMEOUT,required=true"`
	LatencyThreshold          time.Duration `env:"LATENCY_THRESHOLD,required=true"`
	RestartInterval           time.Duration `env:"RESTART_INTERVAL,required=true"`
	AuthTokenDuration         time.Duration `env:"AUTH_TOKEN_DURATION,required=true"`
	BadgerFilepath            string        `env:"BADGER_FILEPATH,required=true"`
	BlugeFilepath             string        `env:"BLUGE_FILEPATH,required=true"`
	LogLevel                  string        `env:"LOG_LEVEL,required=true"`
	LowCapacityThreshold      int           `env:"LOW_CAPACITY_THRESHOLD,required=true"`
	MaxContentLength          int           `env:"MAX_CONTENT_LENGTH,required=true"`
	MinScoring                float64       `env:"MIN_SCORING,required=true"`
	MaxScoring                float64       `env:"MAX_SCORING,required=true"`
	Host                      string        `env:"HOST"`
	Port                      int           `env:"PORT"`
	MaxSpecialistBootDuration time.Duration `env:"MAX_SPECIALIST_BOOT_DURATION"`
	ToxicityBinPath           string        `env:"TOXICITY_BIN_PATH"`
	ToxicityPort              int           `env:"TOXICITY_PORT"`
	SentimentBinPath          string        `env:"SENTIMENT_BIN_PATH"`
	SentimentPort             int           `env:"SENTIMENT_PORT"`
}

func (c Config) CharacterRune() (rune, error) {
	r := []rune(c.CharReplacement)
	if len(r) != 1 {
		return 0, fmt.Errorf(
			"CHARACTER_REPLACEMENT must be a single character, got %q",
			c.CharReplacement,
		)
	}
	return r[0], nil
}
