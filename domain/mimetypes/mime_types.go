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

	AudioMPEG  MIME = "audio/mpeg"
	AudioWAV   MIME = "audio/wav"
	AudioXAIFF MIME = "audio/x-aiff"

	VideoMP4 MIME = "video/mp4"
)

func Matches(detected string, expected MIME) (MIME, bool) {
	mt, _, err := mime.ParseMediaType(detected)
	if err != nil {
		return Unknown, false
	}
	return expected, mt == string(expected)
}

func IsPDF(detected string) (MIME, bool) {
	mimeType, ok := Matches(detected, ApplicationPDF)
	if ok {
		return mimeType, true
	}
	return Unknown, false
}

func IsImage(detected string) (MIME, bool) {
	found, ok := Matches(detected, ImagePNG)
	if ok {
		return found, true
	}
	found, ok = Matches(detected, ImageJPEG)
	if ok {
		return found, true
	}
	found, ok = Matches(detected, ImageGIF)
	if ok {
		return found, true
	}
	return Unknown, false
}

func IsAudio(detected string) (MIME, bool) {
	found, ok := Matches(detected, AudioMPEG)
	if ok {
		return found, true
	}
	found, ok = Matches(detected, AudioWAV)
	if ok {
		return found, true
	}
	found, ok = Matches(detected, AudioXAIFF)
	if ok {
		return found, true
	}
	return Unknown, false
}

func IsVideo(detected MIME) bool {
	return detected == VideoMP4
}

func IsAuthorized(detected string) (MIME, bool) {
	mimeType, ok := IsAudio(detected)
	if ok {
		return mimeType, ok
	}
	mimeType, ok = IsPDF(detected)
	if ok {
		return mimeType, ok
	}
	mimeType, ok = IsImage(detected)
	if ok {
		return mimeType, ok
	}
	return Unknown, false
}

func ToMIME(m string) MIME {
	switch MIME(m) {
	case AudioMPEG, AudioWAV, AudioXAIFF:
		return MIME(m)
	case ApplicationPDF:
		return ApplicationPDF
	case ImagePNG, ImageJPEG, ImageGIF:
		return MIME(m)
	case VideoMP4:
		return MIME(m)
	default:
		return Unknown
	}
}

// Kind groups MIME types into the families a user thinks in.
type Kind string

const (
	KindPDF   Kind = "pdf"
	KindAudio Kind = "audio"
	KindImage Kind = "image"
)

// KindOf returns the family of a detected MIME type, or "" when the type is
// not one the specialists handle.
func KindOf(detected string) Kind {
	if _, ok := IsPDF(detected); ok {
		return KindPDF
	}
	if _, ok := IsAudio(detected); ok {
		return KindAudio
	}
	if _, ok := IsImage(detected); ok {
		return KindImage
	}
	return ""
}

// ParseKind validates a kind received from outside ("pdf", "audio", "image").
func ParseKind(s string) (Kind, bool) {
	switch Kind(s) {
	case KindPDF, KindAudio, KindImage:
		return Kind(s), true
	default:
		return "", false
	}
}
