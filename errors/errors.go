package errors

import "fmt"

var (
	ErrWorkerPanic       = fmt.Errorf("worker panic")
	ErrOnlyCensoredFiles = fmt.Errorf("censored directory contains directories")
	ErrEmptyWords        = fmt.Errorf("no words have been found")
)
