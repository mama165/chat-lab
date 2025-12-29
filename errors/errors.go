package errors

import "fmt"

var (
	ErrWorkerPanic = fmt.Errorf("worker panic")
)
