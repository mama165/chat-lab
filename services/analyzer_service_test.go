package services

import (
	"chat-lab/domain/analyzer"
	"chat-lab/mocks"
	"context"
	"log/slog"
	"strings"
	"testing"
	"time"

	"github.com/mama165/sdk-go/logs"
	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func TestAnalyzerService_Analyze(t *testing.T) {
	req := require.New(t)
	ctx := context.Background()
	log := logs.GetLoggerFromLevel(slog.LevelDebug)
	ctrl := gomock.NewController(t)
	repository := mocks.NewMockIAnalysisRepository(ctrl)
	service := NewAnalyzerService(log, repository, 1000, &analyzer.CountAnalyzedFiles{})

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
				r.MagicBytes = make([]byte, 65)
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
			err := service.Analyze(ctx, analyzer.FileAnalyzerRequest{})
			req.Equal(tt.wantErr, err != nil, tt.description)
		})
	}
}
