package auth

import (
	"github.com/golang-jwt/jwt/v5"
	"time"
)

// jwtKey is the secret used to sign tokens.
// In a production environment, this should be loaded from an environment variable or a secret manager.
var jwtKey = []byte("my_strong_and_long_secret_key_2026")

// CustomClaims defines the structure of the data stored inside the JWT.
type CustomClaims struct {
	UserID string   `json:"user_id"`
	Roles  []string `json:"roles"`
	jwt.RegisteredClaims
}

// GenerateToken creates a signed JWT for a specific user.
func GenerateToken(userID string, roles []string,
	authTokenDuration time.Duration) (string, error) {
	// Set token expiration to 24 hours from now.
	expirationTime := time.Now().Add(authTokenDuration)

	claims := &CustomClaims{
		UserID: userID,
		Roles:  roles,
		RegisteredClaims: jwt.RegisteredClaims{
			ExpiresAt: jwt.NewNumericDate(expirationTime),
			IssuedAt:  jwt.NewNumericDate(time.Now()),
			Issuer:    "chat-lab",
		},
	}

	// Create the token using the HS256 algorithm (HMAC with SHA256).
	token := jwt.NewWithClaims(jwt.SigningMethodHS256, claims)

	// Sign the token with the server's secret key.
	return token.SignedString(jwtKey)
}

// ValidateToken parses and validates the signature and expiration of a JWT string.
func ValidateToken(tokenString string) (*CustomClaims, error) {
	// Parse the token with the custom claims structure.
	token, err := jwt.ParseWithClaims(tokenString, &CustomClaims{}, func(token *jwt.Token) (interface{}, error) {
		// Provide the secret key for validation.
		return jwtKey, nil
	})

	if err != nil {
		return nil, err
	}

	// Verify if the token is valid and extract the claims.
	if claims, ok := token.Claims.(*CustomClaims); ok && token.Valid {
		return claims, nil
	}

	return nil, jwt.ErrSignatureInvalid
}
