package services

import (
	"chat-lab/auth"
	"chat-lab/errors"
	"chat-lab/mocks"
	"chat-lab/repositories"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
	"go.uber.org/mock/gomock"
)

func TestAuthService_Register(t *testing.T) {
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	mockRepo := mocks.NewMockIUserRepository(ctrl)
	svc := NewAuthService(mockRepo, 24*time.Hour)

	t.Run("should register successfully when input is valid", func(t *testing.T) {
		req := require.New(t)
		email := "test@example.com"
		password := "ComplexPass123!" // Must satisfy your complexity rules
		expectedUserID := "user-uuid"

		// Expect CreateUser to be called with a hashed password (not the plain one)
		mockRepo.EXPECT().
			CreateUser(email, gomock.Any()).
			Return(expectedUserID, nil).
			Times(1)

		token, err := svc.Register(email, password)

		req.NoError(err)
		req.NotEmpty(token)
	})

	t.Run("should fail when password complexity is not met", func(t *testing.T) {
		req := require.New(t)
		email := "test@example.com"
		password := "simple" // Fails validation

		// Repository should NEVER be called
		mockRepo.EXPECT().CreateUser(gomock.Any(), gomock.Any()).Times(0)

		token, err := svc.Register(email, password)

		req.Error(err)
		req.ErrorIs(err, errors.ErrInvalidPassword)
		req.Empty(token)
	})

	t.Run("should fail when user already exists in repository", func(t *testing.T) {
		req := require.New(t)
		email := "duplicate@example.com"
		password := "ComplexPass123!"

		mockRepo.EXPECT().
			CreateUser(email, gomock.Any()).
			Return("", errors.ErrUserAlreadyExists).
			Times(1)

		_, err := svc.Register(email, password)

		req.ErrorIs(err, errors.ErrUserAlreadyExists)
	})
}

func TestAuthService_Login(t *testing.T) {
	ctrl := gomock.NewController(t)
	defer ctrl.Finish()

	mockRepo := mocks.NewMockIUserRepository(ctrl)
	svc := NewAuthService(mockRepo, 24*time.Hour)

	t.Run("should login successfully with correct credentials", func(t *testing.T) {
		req := require.New(t)
		email := "user@example.com"
		password := "Secret123456!"

		hashedPassword, _ := auth.HashPassword(password)
		storedUser := repositories.User{
			ID:           "uuid-123",
			Email:        email,
			PasswordHash: hashedPassword,
			Roles:        []string{"user"},
		}

		mockRepo.EXPECT().
			GetUserByEmail(email).
			Return(storedUser, nil).
			Times(1)

		token, err := svc.Login(email, password)

		req.NoError(err)
		req.NotEmpty(token)

		// Optional: validate token claims
		claims, err := auth.ValidateToken(string(token))
		req.NoError(err)
		req.Equal(storedUser.ID, claims.UserID)
	})

	t.Run("should return invalid credentials when password matches nothing", func(t *testing.T) {
		req := require.New(t)
		email := "user@example.com"

		hashedPassword, _ := auth.HashPassword("CorrectPassword123!")
		storedUser := repositories.User{
			Email:        email,
			PasswordHash: hashedPassword,
		}

		mockRepo.EXPECT().
			GetUserByEmail(email).
			Return(storedUser, nil).
			Times(1)

		_, err := svc.Login(email, "WrongPassword123!")

		req.ErrorIs(err, errors.ErrInvalidCredentials)
	})

	t.Run("should return invalid credentials when user is not found", func(t *testing.T) {
		req := require.New(t)

		mockRepo.EXPECT().
			GetUserByEmail("unknown@example.com").
			Return(repositories.User{}, errors.ErrInvalidCredentials).
			Times(1)

		_, err := svc.Login("unknown@example.com", "anyPassword")

		req.ErrorIs(err, errors.ErrInvalidCredentials)
	})
}
