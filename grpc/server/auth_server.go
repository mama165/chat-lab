package server

import (
	"chat-lab/errors"
	pb "chat-lab/proto/account"
	"chat-lab/services"
	"context"
)

type AuthServer struct {
	pb.UnimplementedAuthServiceServer
	authService services.IAuthService
}

// NewAuthServer creates a new gRPC server for authentication.
func NewAuthServer(authService services.IAuthService) *AuthServer {
	return &AuthServer{authService: authService}
}

// Register handles user registration by validating input, hashing password and issuing a token.
func (s *AuthServer) Register(ctx context.Context, in *pb.RegisterRequest) (*pb.AuthResponse, error) {
	token, err := s.authService.Register(in.GetEmail(), in.GetPassword())
	if err != nil {
		return nil, errors.MapToGRPCError(err)
	}

	return &pb.AuthResponse{
		Token: string(token),
		// UserId is currently empty as the service only returns the token.
		// We can extract it from JWT in the future or change service signature.
		UserId: "",
	}, nil
}

// Login verifies credentials and returns a session token.
func (s *AuthServer) Login(ctx context.Context, in *pb.LoginRequest) (*pb.AuthResponse, error) {
	token, err := s.authService.Login(in.GetEmail(), in.GetPassword())
	if err != nil {
		return nil, errors.MapToGRPCError(err)
	}

	return &pb.AuthResponse{
		Token:  string(token),
		UserId: "",
	}, nil
}
