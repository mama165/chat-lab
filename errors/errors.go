package errors

import (
	"context"
	"errors"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/status"
)

var (
	ErrWorkerPanic        = errors.New("worker panic")
	ErrEmptyWords         = errors.New("no words have been found")
	ErrInvalidPayload     = errors.New("payload of event has a wrong type")
	ErrServerOverloaded   = errors.New("too many messages, please backoff")
	ErrNoValidPatterns    = errors.New("no valid patterns after normalization")
	ErrContentTooLarge    = errors.New("content  too large")
	ErrUserAlreadyExists  = errors.New("user already exists")
	ErrInvalidCredentials = errors.New("invalid credentials")
	ErrTokenGeneration    = errors.New("token generation failed")
	ErrInvalidPassword    = errors.New("password must " +
		"contain at least one uppercase, one lowercase")
)

func MapToGRPCError(err error) error {
	if err == nil {
		return nil
	}
	switch {
	// Client errors (4xx)
	case errors.Is(err, ErrContentTooLarge), errors.Is(err, ErrInvalidPassword):
		return status.Error(codes.InvalidArgument, err.Error())
	case errors.Is(err, ErrInvalidCredentials):
		return status.Error(codes.Unauthenticated, err.Error())
	case errors.Is(err, ErrUserAlreadyExists):
		return status.Error(codes.AlreadyExists, err.Error())

	// Server capacity errors
	case errors.Is(err, ErrServerOverloaded):
		return status.Error(codes.ResourceExhausted, err.Error())

	// Context errors
	case errors.Is(err, context.DeadlineExceeded):
		return status.Error(codes.DeadlineExceeded, "request took too long")
	case errors.Is(err, context.Canceled):
		return status.Error(codes.Canceled, "client canceled the request")

	// Server errors (5xx)
	case errors.Is(err, ErrTokenGeneration):
		return status.Error(codes.Internal, "security token could not be issued")

	default:
		return status.Error(codes.Internal, "an unexpected error occurred")
	}
}
