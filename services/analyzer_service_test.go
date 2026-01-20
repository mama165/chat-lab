package services

import (
	"chat-lab/domain/analyzer"
	"github.com/mama165/sdk-go/logs"
	"github.com/stretchr/testify/require"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func TestAnalyzerService_Analyze(t *testing.T) {
	req := require.New(t)
	service := NewAnalyzerService(logs.GetLoggerFromLevel(slog.LevelDebug))

	baseRequest := analyzer.FileAnalyzerRequest{
		Path:       "E:/Photos/vacances.jpg",
		DriveID:    "UUID-999",
		Size:       2048,
		Attributes: 32,
		MimeType:   "image/jpeg",
		MagicBytes: []byte{0xFF, 0xD8},
		ScannedAt:  time.Now(),
		SourceType: analyzer.LOCALFIXED,
	}

	tests := []struct {
		description string
		modify      func(r *analyzer.FileAnalyzerRequest)
		wantErr     bool
	}{
		{
			"Should succeed with valid data",
			func(r *analyzer.FileAnalyzerRequest) {},
			false,
		},
		{
			"Should fail if Path is empty",
			func(r *analyzer.FileAnalyzerRequest) { r.Path = "" },
			true,
		},
		{
			"Should fail if Path exceeds 1024 characters",
			func(r *analyzer.FileAnalyzerRequest) {
				r.Path = strings.Repeat("a", 1025)
			},
			true,
		},
		{
			"Should fail if MagicBytes exceeds 512 bytes",
			func(r *analyzer.FileAnalyzerRequest) {
				r.MagicBytes = make([]byte, 513)
			},
			true,
		},
		{
			"Should fail if SourceType is invalid",
			func(r *analyzer.FileAnalyzerRequest) {
				r.SourceType = "CLOUD_STORAGE"
			},
			true,
		},
		{
			"Should fail if MimeType is missing",
			func(r *analyzer.FileAnalyzerRequest) { r.MimeType = "" },
			true,
		},
		{
			"Should fail if ScannedAt is zero-value",
			func(r *analyzer.FileAnalyzerRequest) { r.ScannedAt = time.Time{} },
			true,
		},
	}

	for _, tt := range tests {
		t.Run(tt.description, func(t *testing.T) {
			tc := baseRequest
			tt.modify(&tc)
			_, err := service.Analyze(tc)
			req.Equal(tt.wantErr, err != nil, tt.description)
		})
	}
}
