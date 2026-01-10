package auth

import (
	"github.com/stretchr/testify/require"
	"strings"
	"testing"
)

func TestHashAndCompare(t *testing.T) {
	req := require.New(t)
	password := "MonMotDePasseTr0pSûr!"

	hash, err := HashPassword(password)
	req.NoError(err)
	req.True(strings.HasPrefix(hash, "$argon2id$"))

	match, err := ComparePassword(password, hash)
	req.NoError(err)
	req.True(match)

	// Test de la comparaison négative (mauvais mot de passe)
	match, err = ComparePassword("MauvaisMDP", hash)
	req.NoError(err)
	req.False(match)
}

// TestRegistrationValidation vérifie tes règles métier strictes (CNIL)
func TestRegistrationValidation(t *testing.T) {
	req := require.New(t)
	tests := []struct {
		name    string
		req     RegisterRequest
		wantErr bool
	}{
		{"Valid request", RegisterRequest{"test@example.com", "ComplexPass123!"}, false},
		{"Invalid email", RegisterRequest{"notanemail", "ComplexPass123!"}, true},
		{"Password too short", RegisterRequest{"test@example.com", "Short1!"}, true},
		{"Missing digit", RegisterRequest{"test@example.com", "NoDigitPass!"}, true},
		{"Missing special char", RegisterRequest{"test@example.com", "NoSpecialChar123"}, true},
		{"Missing uppercase", RegisterRequest{"test@example.com", "nouppercase123!"}, true},
		{"Password too long (edge case)", RegisterRequest{"test@example.com", strings.Repeat("a", 73)}, true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			err := ValidateRegister(tt.req)
			if tt.wantErr {
				req.Error(err)
			} else {
				req.NoError(err)
			}
		})
	}
}

// BenchmarkHashPassword permet de mesurer l'impact CPU/RAM (Crucial pour K8s)
func BenchmarkHashPassword(b *testing.B) {
	for i := 0; i < b.N; i++ {
		_, _ = HashPassword("A-very-long-and-complex-password-for-bench-123!")
	}
}
