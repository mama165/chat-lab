package auth

import (
	"chat-lab/errors"
	"unicode"

	"github.com/go-playground/validator/v10"
)

var validate = validator.New()

type RegisterRequest struct {
	Email    string `validate:"required,email"`
	Password string `validate:"required,min=12,max=72"`
}

func ValidateRegister(req RegisterRequest) error {
	if err := validate.Struct(req); err != nil {
		return err
	}

	if !isPasswordComplex(req.Password) {
		return errors.ErrInvalidPassword
	}
	return nil
}

func isPasswordComplex(s string) bool {
	var (
		hasUpper   = false
		hasLower   = false
		hasNumber  = false
		hasSpecial = false
	)
	for _, char := range s {
		switch {
		case unicode.IsUpper(char):
			hasUpper = true
		case unicode.IsLower(char):
			hasLower = true
		case unicode.IsNumber(char):
			hasNumber = true
		case unicode.IsPunct(char) || unicode.IsSymbol(char):
			hasSpecial = true
		}
	}
	return hasUpper && hasLower && hasNumber && hasSpecial
}
