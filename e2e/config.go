package e2e

import (
	"github.com/kelseyhightower/envconfig"
)

type Config struct {
	MasterAddr  string `envconfig:"MASTER_ADDR" default:"localhost:50051"`
	ScannerAddr string `envconfig:"SCANNER_ADDR" default:"localhost:50052"`
	// E2E_DEBUG_JSON allows dumping full gRPC request/response bodies as JSON
	DebugJSON bool `envconfig:"E2E_DEBUG_JSON" default:"false"`
	// E2E_COLOURS enables colorized output for better log readability
	Colours bool   `envconfig:"E2E_COLOURS" default:"true"`
	rootDir string `envconfig:"ROOT_DIR"`
}

func LoadConfig() (Config, error) {
	var cfg Config
	err := envconfig.Process("", &cfg)
	return cfg, err
}
