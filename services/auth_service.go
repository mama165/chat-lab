package services

import (
	"chat-lab/auth"
	"chat-lab/errors"
	"chat-lab/repositories"
	"fmt"
)

type IAuthService interface {
	Login(email, password string) (Token, error)
	Register(email, password string) (Token, error)
}

type AuthService struct {
	userRepository repositories.IUserRepository
}

type Token string

func (t Token) string() string {
	return string(t)
}

func NewAuthService(repo repositories.IUserRepository) IAuthService {
	return &AuthService{userRepository: repo}
}

func (s *AuthService) Register(email, password string) (Token, error) {
	valReq := auth.RegisterRequest{
		Email:    email,
		Password: password,
	}

	// 1. Validate business rules (email format, password complexity)
	// We check this before any expensive cryptographic operation.
	if err := auth.ValidateRegister(valReq); err != nil {
		return "", fmt.Errorf("%w: %v", errors.ErrInvalidPassword, err)
	}

	// 2. Hash the password using Argon2id
	// Done in the service layer to keep the repository unaware of plain passwords.
	hashedPassword, err := auth.HashPassword(password)
	if err != nil {
		return "", fmt.Errorf("hashing failed: %w", err)
	}

	// 3. Persist the user with the generated hash
	userID, err := s.userRepository.CreateUser(email, hashedPassword)
	if err != nil {
		return "", err // Will propagate ErrUserAlreadyExists if email is taken
	}

	// 4. Generate the initial session token
	token, err := auth.GenerateToken(userID, []string{"user"})
	if err != nil {
		return "", errors.ErrTokenGeneration
	}

	return Token(token), nil
}

func (s *AuthService) Login(email, password string) (Token, error) {
	// 1. Retrieve user by email from storage
	user, err := s.userRepository.GetUserByEmail(email)
	if err != nil {
		// Generic error to prevent user enumeration attacks
		return "", errors.ErrInvalidCredentials
	}

	// 2. Compare the provided password with the stored hash
	match, err := auth.ComparePassword(password, user.PasswordHash)
	if err != nil || !match {
		return "", errors.ErrInvalidCredentials
	}

	// 3. Issue the JWT token
	token, err := auth.GenerateToken(user.ID, user.Roles)
	if err != nil {
		return "", errors.ErrTokenGeneration
	}

	return Token(token), nil
}
