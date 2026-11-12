package internal

import (
	"fmt"
	"time"
)

type Config struct {
	BufferSize      int           `env:"BUFFER_SIZE,required=true"`
	NumberOfWorkers int           `env:"NUMBER_OF_WORKERS,required=true"`
	MetricInterval  time.Duration `env:"METRIC_INTERVAL,required=true"`

	BufferTimeout     time.Duration `env:"BUFFER_TIMEOUT,required=true"`
	SpecialistTimeout time.Duration `env:"SPECIALIST_TIMEOUT,required=true"`

	RestartInterval           time.Duration `env:"RESTART_INTERVAL,required=true"`
	BadgerFilepath            string        `env:"BADGER_FILEPATH,required=true"`
	BlugeFilepath             string        `env:"BLUGE_FILEPATH,required=true"`
	LogLevel                  string        `env:"LOG_LEVEL,required=true"`
	LowCapacityThreshold      int           `env:"LOW_CAPACITY_THRESHOLD,required=true"`
	MaxAnalyzedEvent          int           `env:"MAX_ANALYZED_EVENT,required=true"`
	MaxSpecialistBootDuration time.Duration `env:"MAX_SPECIALIST_BOOT_DURATION,required=true"`
	EnableSpecialists         bool          `env:"ENABLE_SPECIALISTS,required=true"`
	// ScanRootDir is the folder scanned at startup; the -dir flag overrides it.
	ScanRootDir     string `env:"SCAN_ROOT_DIR"`
	ScannerWorkerNb int    `env:"SCANNER_WORKER_NB,required=true"`
	// AnalysisWorkerNb is the number of files sent to the specialists at the same time.
	AnalysisWorkerNb int `env:"ANALYSIS_WORKER_NB,required=true"`
	// MonitoringHost is the address the HTTP server (search, monitoring) listens on.
	// The default only accepts connections from this machine.
	MonitoringHost                   string `env:"MONITORING_HOST,default=127.0.0.1"`
	MonitoringPort                   int    `env:"MONITORING_PORT,default=8092"`
	DriveID                          string `env:"DRIVE_ID,required=true"`
	ScannerBackpressureLowThreshold  int    `env:"SCANNER_BACKPRESSURE_LOW_THRESHOLD_PERCENT,required=true"`
	ScannerBackpressureHardThreshold int    `env:"SCANNER_BACKPRESSURE_HARD_THRESHOLD_PERCENT,required=true"`
	MaxFileSizeMb                    int    `env:"MAX_FILE_SIZE_MB,required=true"`
	GrpcConfig                       GrpcConfig
}

type GrpcConfig struct {
	MaxAttempts       int           `env:"GRPC_RETRY_MAX_ATTEMPTS,default=5"`
	InitialBackoff    time.Duration `env:"GRPC_RETRY_INITIAL_BACKOFF,default=100ms"`
	MaxBackoff        time.Duration `env:"GRPC_RETRY_MAX_BACKOFF,default=1s"`
	BackoffMultiplier float64       `env:"GRPC_RETRY_BACKOFF_MULTIPLIER,default=2.0"`
}

// ToServiceConfig generate JSON retry for a given service
// On utilise .Seconds() car gRPC JSON attend un format string se terminant par 's'.
func (g GrpcConfig) ToServiceConfig(serviceName string) string {
	return fmt.Sprintf(`{
        "methodConfig": [{
            "name": [{"service": "%s"}],
            "waitForReady": true,
            "retryPolicy": {
                "MaxAttempts": %d,
                "InitialBackoff": "%.3fs",
                "MaxBackoff": "%.3fs",
                "BackoffMultiplier": %.1f,
                "RetryableStatusCodes": ["UNAVAILABLE"]
            }
        }]
    }`, serviceName, g.MaxAttempts, g.InitialBackoff.Seconds(), g.MaxBackoff.Seconds(), g.BackoffMultiplier)
}
