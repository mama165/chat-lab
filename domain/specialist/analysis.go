package specialist

// Category defines the type of content being processed by a specialist.
type Category string

const (
	// TextType represents plain text or markdown content.
	TextType Category = "text"
	// AudioType represents sound files or streams.
	AudioType Category = "audio"
	// FileType represents generic binary files or documents.
	FileType Category = "file"
)

// Request represents a single data chunk sent to a specialist for analysis.
type Request struct {
	Metadata *Metadata
	Chunk    []byte
}

// Metadata contains descriptive information about the message or file.
type Metadata struct {
	MessageID string
	FileName  string
	MimeType  string
}

// Response is a container for the specialist's output.
// OneOf can hold different result types (e.g., Score, DocumentData).
type Response struct {
	OneOf any
}

// DocumentData holds structured information extracted from a document.
type DocumentData struct {
	Title     string
	Author    string
	PageCount int32
	Language  string
	Pages     []Page
}

// Page represents a single unit of text within a DocumentData.
type Page struct {
	Number  int32
	Content string
}

// Score represents a classification result, such as toxicity level or sentiment.
type Score struct {
	Score float64
	Label string
}

type AnalysisRequest struct {
	Path     string
	MimeType string
}

type AnalysisResponse struct {
	Results map[Metric]Response
}
