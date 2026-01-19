package specialist

type Category string

const (
	TextType  Category = "text"
	AudioType Category = "audio"
	FileType  Category = "file"
)

type Request struct {
	MessageID string
	Content   string
	Tags      map[string]string
}

type Response struct {
	Score            float64
	Label            string
	Version          string
	ProcessingTimeMs int
	Status           string
}
