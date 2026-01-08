package errors

import "fmt"

var (
	ErrWorkerPanic    = fmt.Errorf("worker panic")
	ErrEmptyWords     = fmt.Errorf("no words have been found")
	ErrInvalidPayload = fmt.Errorf("payload of event has a wrong type")
)
