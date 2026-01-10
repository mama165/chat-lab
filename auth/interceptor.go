package auth

import (
	"context"
	"strings"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// AuthInterceptor handles JWT validation for incoming gRPC calls
func AuthInterceptor(ctx context.Context, req any, info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
	// 1. Skip authentication for Login and Register methods
	if isPublicMethod(info.FullMethod) {
		return handler(ctx, req)
	}

	// 2. Extract metadata (headers)
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return nil, status.Error(codes.Unauthenticated, "metadata is missing")
	}

	// 3. Get Authorization header
	values := md.Get("authorization")
	if len(values) == 0 {
		return nil, status.Error(codes.Unauthenticated, "authorization token is missing")
	}

	// Expecting "Bearer <token>"
	tokenStr := strings.TrimPrefix(values[0], "Bearer ")

	// 4. Use ValidateToken to check the JWT
	claims, err := ValidateToken(tokenStr)
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "invalid or expired token")
	}

	// 5. Inject user info into context for the service layer
	newCtx := context.WithValue(ctx, "user_id", claims.UserID)
	newCtx = context.WithValue(newCtx, "roles", claims.Roles)

	return handler(newCtx, req)
}

func isPublicMethod(method string) bool {
	// Methods that don't require a token
	publicMethods := []string{
		"/account.AuthService/Login",
		"/account.AuthService/Register",
	}
	for _, m := range publicMethods {
		if m == method {
			return true
		}
	}
	return false
}
