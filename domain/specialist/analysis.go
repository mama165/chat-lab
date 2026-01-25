package specialist

type Category string

const (
	TextType  Category = "text"
	AudioType Category = "audio"
	FileType  Category = "file"
)

type Request struct {
	Metadata *Metadata
	Chunk    []byte
}

type Metadata struct {
	MessageID string
	FileName  string
	MimeType  string
}

type Response struct {
	OneOf any
}

type DocumentData struct {
	Title     string
	Author    string
	PageCount int32
	Language  string
	Pages     []Page
}

type Page struct {
	Number  int32
	Content string
}

type Score struct {
	Score float64
	Label string
}
