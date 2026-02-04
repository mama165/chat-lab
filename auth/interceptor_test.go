package auth_test

import (
	"chat-lab/auth"
	"chat-lab/infrastructure/grpc/server"
	pb "chat-lab/proto/account"
	pb2 "chat-lab/proto/chat"
	"context"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"google.golang.org/grpc"
	"google.golang.org/grpc/codes"
	"google.golang.org/grpc/metadata"
	"google.golang.org/grpc/status"
)

func TestAuthInterceptor(t *testing.T) {
	// Setup a dummy handler that returns the context it received
	// This allows us to inspect if user_id was correctly injected
	dummyHandler := func(ctx context.Context, req any) (any, error) {
		return ctx, nil
	}

	t.Run("should allow public methods without token", func(t *testing.T) {
		req := require.New(t)
		ctx := context.Background()
		info := &grpc.UnaryServerInfo{
			FullMethod: pb.AuthService_Login_FullMethodName,
		}

		resCtx, err := server.AuthInterceptor(true)(ctx, nil, info, dummyHandler)

		req.NoError(err)
		req.NotNil(resCtx)
	})

	t.Run("should fail when metadata is missing on protected method", func(t *testing.T) {
		req := require.New(t)
		ctx := context.Background()
		info := &grpc.UnaryServerInfo{
			FullMethod: pb2.ChatService_PostMessage_FullMethodName,
		}

		_, err := server.AuthInterceptor(true)(ctx, nil, info, dummyHandler)

		req.Error(err)
		st, ok := status.FromError(err)
		req.True(ok)
		req.Equal(codes.Unauthenticated, st.Code())
	})

	t.Run("should fail with invalid token", func(t *testing.T) {
		req := require.New(t)
		// Provide an invalid Bearer token
		md := metadata.Pairs("authorization", "Bearer invalid-token-string")
		ctx := metadata.NewIncomingContext(context.Background(), md)

		info := &grpc.UnaryServerInfo{
			FullMethod: pb2.ChatService_PostMessage_FullMethodName,
		}

		_, err := server.AuthInterceptor(true)(ctx, nil, info, dummyHandler)

		req.Error(err)
		req.Contains(err.Error(), "invalid or expired token")
	})

	t.Run("should succeed and inject user_id when token is valid", func(t *testing.T) {
		req := require.New(t)

		// 1. Generate a valid token for testing
		userID := "user-123"
		roles := []string{"admin"}
		token, err := auth.GenerateToken(userID, roles, 1*time.Hour)
		req.NoError(err)

		// 2. Setup context with metadata
		md := metadata.Pairs("authorization", "Bearer "+token)
		ctx := metadata.NewIncomingContext(context.Background(), md)

		info := &grpc.UnaryServerInfo{
			FullMethod: pb2.ChatService_PostMessage_FullMethodName,
		}

		// 3. Call the interceptor
		resCtx, err := server.AuthInterceptor(true)(ctx, nil, info, dummyHandler)

		// 4. Assertions
		req.NoError(err)

		// Verify the context was enriched with user information
		resultCtx := resCtx.(context.Context)
		req.Equal(userID, resultCtx.Value(server.UserIDKey))
		req.Equal(roles, resultCtx.Value(server.RolesKey))
	})
}
