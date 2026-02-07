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

func IsImage(detected string) bool {
	_, ok1 := Matches(detected, ImagePNG)
	_, ok2 := Matches(detected, ImageJPEG)
	_, ok3 := Matches(detected, ImageGIF)
	return ok1 || ok2 || ok3
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
	return Unknown, false
}

func ToMIME(m string) MIME {
	switch MIME(m) {
	case AudioMPEG, AudioWAV, AudioXAIFF:
		return MIME(m)
	case ApplicationPDF:
		return ApplicationPDF
	case VideoMP4:
		return MIME(m)
	default:
		return Unknown
	}
}
