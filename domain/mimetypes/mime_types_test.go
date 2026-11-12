package mimetypes

import (
	"testing"

	"github.com/stretchr/testify/require"
)

func TestMatches(t *testing.T) {
	tests := []struct {
		name     string
		detected string
		expected MIME
		want     bool
	}{
		// Text types
		{"Plain text with charset", "text/plain; charset=utf-8", TextPlain, true},
		{"HTML text", "text/html; charset=utf-8", TextHTML, true},
		{"CSS text", "text/css", TextCSS, true},

		// Application types
		{"JSON", "application/json", ApplicationJSON, true},
		{"JSON with charset", "application/json; charset=utf-8", ApplicationJSON, true},
		{"PDF", "application/pdf", ApplicationPDF, true},
		{"XML detected as text/xml", "text/xml; charset=utf-8", ApplicationXML, false}, // attention
		{"XML detected as application/xml", "application/xml", ApplicationXML, true},

		// Image types
		{"PNG", "image/png", ImagePNG, true},
		{"JPEG", "image/jpeg", ImageJPEG, true},
		{"GIF", "image/gif", ImageGIF, true},

		// audio types
		{"MPEG4", "audio/mpeg", AudioMPEG, true},
		{"WAV", "audio/wav", AudioWAV, true},
		{"XAIFF", "audio/x-aiff", AudioXAIFF, true},

		// video types
		{"MPEG4", "video/mp4", VideoMP4, true},

		// Fallback / mismatch
		{"Mismatch", "text/plain; charset=utf-8", ApplicationJSON, false},
		{"Unknown type", "application/octet-stream", TextPlain, false},
		{"Invalid MIME", "not a mime", TextPlain, false},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			got, ok := Matches(tt.detected, tt.expected)
			if ok != tt.want && got != tt.expected {
				t.Errorf("Matches(%q, %q) = %v; want %v", tt.detected, tt.expected, ok, tt.want)
			}
		})
	}
}

func TestIsAudio(t *testing.T) {
	req := require.New(t)
	tests := []struct {
		name     string
		detected string
		expected MIME
		want     bool
	}{
		{"MPEG4", "audio/mpeg", AudioMPEG, true},
		{"WAV", "audio/wav", AudioWAV, true},
		{"XAIFF", "audio/x-aiff", AudioXAIFF, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, ok := IsAudio(tt.detected)
			req.Equal(tt.want, ok)
		})
	}
}

func TestIsImage(t *testing.T) {
	req := require.New(t)
	tests := []struct {
		name     string
		detected string
		expected MIME
		want     bool
	}{
		{"PNG", "image/png", ImagePNG, true},
		{"JPEG", "image/jpeg", ImageJPEG, true},
		{"GIF", "image/gif", ImageGIF, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			_, ok := IsImage(tt.detected)
			req.Equal(tt.want, ok)
		})
	}
}

func TestKindOf(t *testing.T) {
	cases := map[string]Kind{
		"application/pdf":           KindPDF,
		"audio/mpeg":                KindAudio,
		"audio/wav":                 KindAudio,
		"image/png":                 KindImage,
		"image/jpeg; charset=utf-8": KindImage,
		"text/plain":                "",
		"not a mime type":           "",
	}
	for detected, want := range cases {
		if got := KindOf(detected); got != want {
			t.Errorf("KindOf(%q) = %q, want %q", detected, got, want)
		}
	}
}

func TestParseKind(t *testing.T) {
	for _, valid := range []string{"pdf", "audio", "image"} {
		if kind, ok := ParseKind(valid); !ok || string(kind) != valid {
			t.Errorf("ParseKind(%q) should be accepted", valid)
		}
	}
	for _, invalid := range []string{"", "video", "PDF"} {
		if _, ok := ParseKind(invalid); ok {
			t.Errorf("ParseKind(%q) should be rejected", invalid)
		}
	}
}
