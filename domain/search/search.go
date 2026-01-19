package search

import (
	"strconv"
	"strings"
)

// Query represents the structured parameters for a War Room search.
// It decouples the raw chat input from the actual database engine requirements.
type Query struct {
	RawInput string             // The original message from the user
	Terms    string             // The actual text to search in Bluge
	Filters  map[string]float64 // Specialist scores (e.g., "business": 0.9)
	RoomID   string             // Target room for the search
	Limit    int                // Pagination: number of results
}

// NewSearchQuery parses a raw string to extract command-line style arguments.
// Example: /find "invoice" --business 0.8 --room 12
func NewSearchQuery(input string) *Query {
	query := &Query{
		RawInput: input,
		Filters:  make(map[string]float64),
		Limit:    10, // Default limit
	}

	parts := strings.Fields(input)
	var textTerms []string

	for i := 0; i < len(parts); i++ {
		part := parts[i]

		// Handle flags like --business 0.8 or --room 4
		if strings.HasPrefix(part, "--") && i+1 < len(parts) {
			key := strings.TrimPrefix(part, "--")
			val := parts[i+1]

			if key == "room" {
				query.RoomID = val
			} else if score, err := strconv.ParseFloat(val, 64); err == nil {
				query.Filters[key] = score
			}
			i++ // Skip the value part in next iteration
			continue
		}

		// If it's not a flag, it's a search term
		if !strings.HasPrefix(part, "/") {
			textTerms = append(textTerms, part)
		}
	}

	query.Terms = strings.Join(textTerms, " ")
	return query
}
