package mimetypes

import "mime"

type MIME string

const (
	Unknown   MIME = "unknown"
	TextPlain MIME = "text/plain"
	TextHTML  MIME = "text/html"
	TextCSS   MIME = "text/css"

	ApplicationPDF  MIME = "application/pdf"
	ApplicationJSON MIME = "application/json"
	ApplicationXML  MIME = "application/xml"

	ImagePNG  MIME = "image/png"
	ImageJPEG MIME = "image/jpeg"
	ImageGIF  MIME = "image/gif"
)

func Matches(detected string, expected MIME) (MIME, bool) {
	mt, _, err := mime.ParseMediaType(detected)
	if err != nil {
		return Unknown, false
	}
	return expected, mt == string(expected)
}
