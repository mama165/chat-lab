package mimetypes

import (
	"testing"
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
