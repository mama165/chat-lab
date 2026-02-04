package e2e

import (
	"github.com/kelseyhightower/envconfig"
)

type Config struct {
	MasterAddr  string `envconfig:"MASTER_ADDR"`
	ScannerAddr string `envconfig:"SCANNER_ADDR"`
	// E2E_DEBUG_JSON allows dumping full gRPC request/response bodies as JSON
	DebugJSON bool `envconfig:"E2E_DEBUG_JSON" default:"false"`
	// E2E_COLOURS enables colorized output for better log readability
	Colours        bool   `envconfig:"E2E_COLOURS" default:"true"`
	ScannerRootDir string `envconfig:"SCANNER_ROOT_DIR"`
}

func LoadConfig() (Config, error) {
	var cfg Config
	err := envconfig.Process("", &cfg)
	return cfg, err
}
