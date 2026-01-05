package main

import "time"

type Config struct {
	BufferSize                int           `env:"BUFFER_SIZE,required=true"`
	ConnectionBufferSize      int           `env:"CONNECTION_BUFFER_SIZE,required=true"`
	NumberOfWorkers           int           `env:"NUMBER_OF_WORKERS,required=true"`
	ModerationCharReplacement rune          `env:"MODERATION_CHARACTER_REPLACEMENT,required=true"`
	LimitMessages             *int          `env:"LIMIT_MESSAGES"`
	SinkTimeout               time.Duration `env:"SINK_TIMEOUT,required=true"`
	RestartInterval           time.Duration `env:"RESTART_INTERVAL,required=true"`
	BadgerFilepath            string        `env:"BADGER_FILEPATH,required=true"`
	LogLevel                  string        `env:"LOG_LEVEL,required=true"`
	Host                      string        `env:"HOST,default=localhost"`
	Port                      int           `env:"PORT,default=8080"`
}
