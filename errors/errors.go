package errors

import (
	"context"
	"errors"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

var (
	ErrWorkerPanic      = errors.New("worker panic")
	ErrEmptyWords       = errors.New("no words have been found")
	ErrInvalidPayload   = errors.New("payload of event has a wrong type")
	ErrServerOverloaded = errors.New("too many messages, please backoff")
	ErrNoValidPatterns  = errors.New("no valid patterns after normalization")
	ErrContentTooLarge  = errors.New("content  too large")
)

func MapToGRPCError(err error) error {
	if err == nil {
		return nil
	}
	switch {
	case errors.Is(err, ErrContentTooLarge):
		return status.Error(codes.InvalidArgument, err.Error())
	case errors.Is(err, ErrServerOverloaded):
		return status.Error(codes.ResourceExhausted, err.Error())
	case errors.Is(err, context.DeadlineExceeded):
		return status.Error(codes.DeadlineExceeded, "request took too long")
	case errors.Is(err, context.Canceled):
		return status.Error(codes.Canceled, "client canceled the request")
	default:
		return status.Error(codes.Internal, "an unexpected error occurred")
	}
}
