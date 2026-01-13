package auth

import (
	"context"
	"strings"

	pb "chat-lab/proto/account"

	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

// Map of methods that do not require JWT authentication.
// Using generated constants from the proto package for type-safety.
var publicMethods = map[string]struct{}{
	pb.AuthService_Login_FullMethodName:    {},
	pb.AuthService_Register_FullMethodName: {},
}

type contextKey string

const (
	UserIDKey contextKey = "user_id"
	RolesKey  contextKey = "roles"
)

// AuthInterceptor handles JWT validation for incoming gRPC calls.
func AuthInterceptor(ctx context.Context, req any,
	info *grpc.UnaryServerInfo, handler grpc.UnaryHandler) (any, error) {
	// 1. Skip authentication for public methods (Login/Register)
	if isPublicMethod(info.FullMethod) {
		return handler(ctx, req)
	}

	// 2. Extract metadata (headers) from the incoming gRPC context
	md, ok := metadata.FromIncomingContext(ctx)
	if !ok {
		return nil, status.Error(codes.Unauthenticated, "metadata is missing")
	}

	// 3. Retrieve and validate the Authorization header
	values := md.Get("authorization")
	if len(values) == 0 {
		return nil, status.Error(codes.Unauthenticated, "authorization token is missing")
	}

	// Expecting the standard "Bearer <token>" format
	tokenStr := strings.TrimPrefix(values[0], "Bearer ")

	// 4. Validate the JWT and extract claims
	claims, err := ValidateToken(tokenStr)
	if err != nil {
		return nil, status.Error(codes.Unauthenticated, "invalid or expired token")
	}

	// 5. Inject user identity into context for downstream service layers
	newCtx := context.WithValue(ctx, UserIDKey, claims.UserID)
	newCtx = context.WithValue(newCtx, RolesKey, claims.Roles)

	// Continue the execution chain with the enriched context
	return handler(newCtx, req)
}

// isPublicMethod checks if the current gRPC method is allowed without a token.
func isPublicMethod(method string) bool {
	_, ok := publicMethods[method]
	return ok
}
