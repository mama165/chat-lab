package errors

import (
	"errors"
)

var (
	ErrWorkerPanic      = errors.New("worker panic")
	ErrEmptyWords       = errors.New("no words have been found")
	ErrInvalidPayload   = errors.New("payload of event has a wrong type")
	ErrServerOverloaded = errors.New("system congested: please retry with exponential backoff")
)
