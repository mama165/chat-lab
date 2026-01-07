// Package runtime handles the infrastructure-level tasks like loading configuration and files.
package runtime

import (
	"bufio"
	"bytes"
	"chat-lab/errors"
	"embed"
	"io/fs"
	"strings"
)

// CensoredData carries the result of the loading process including metadata for logging.
type CensoredData struct {
	Words     []string
	Languages []string
}

// CensoredLoader is responsible for reading and parsing blacklisted words from embedded files.
type CensoredLoader struct {
	fs embed.FS
}

// NewCensoredLoader creates a new instance of CensoredLoader with the provided embedded filesystem.
func NewCensoredLoader(f embed.FS) *CensoredLoader {
	return &CensoredLoader{fs: f}
}

// LoadAll scans the given directory path in the embedded FS, identifying .txt files
// as language dictionaries and parsing their contents into a unique list of words.
func (l *CensoredLoader) LoadAll(path string) (*CensoredData, error) {
	entries, err := fs.ReadDir(l.fs, path)
	if err != nil {
		return nil, err
	}

	var languages []string
	uniqueWords := make(map[string]struct{})

	for _, entry := range entries {
		// We only process files, skipping subdirectories
		if entry.IsDir() {
			continue
		}

		// Track the language based on the filename (e.g., "fr.txt" -> "fr")
		lang := strings.TrimSuffix(entry.Name(), ".txt")
		languages = append(languages, lang)

		// Read the file content
		data, err := l.fs.ReadFile(path + "/" + entry.Name())
		if err != nil {
			return nil, err
		}

		// Use a scanner to handle different line endings (\n vs \r\n) correctly
		// ⚠️Don't use strings.Split
		scanner := bufio.NewScanner(bytes.NewReader(data))
		for scanner.Scan() {
			line := strings.TrimSpace(scanner.Text())
			if line != "" {
				uniqueWords[line] = struct{}{}
			}
		}

		if err := scanner.Err(); err != nil {
			return nil, err
		}
	}

	if len(uniqueWords) == 0 {
		return nil, errors.ErrEmptyWords
	}

	// Convert the map of unique words into a slice
	words := make([]string, 0, len(uniqueWords))
	for w := range uniqueWords {
		words = append(words, w)
	}

	return &CensoredData{
		Words:     words,
		Languages: languages,
	}, nil
}
