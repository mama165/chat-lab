//go:generate go run go.uber.org/mock/mockgen -source=user.go -destination=../mocks/mock_user_repository.go -package=mocks
package repositories

import (
	"chat-lab/errors"
	pb "chat-lab/proto/account"
	"fmt"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"google.golang.org/protobuf/proto"
)

type IUserRepository interface {
	CreateUser(email, hashedPassword string) (string, error)
	GetUserByEmail(email string) (User, error)
}

type UserRepository struct {
	db *badger.DB
}

func NewUserRepository(db *badger.DB) IUserRepository {
	return &UserRepository{db: db}
}

// User is the domain-friendly representation of a user in the repository layer.
// Equivalent to DiskMessage for the account domain.
type User struct {
	ID           string
	Email        string
	PasswordHash string
	Roles        []string
	CreatedAt    time.Time
}

// CreateUser hashes the password and persists the user in BadgerDB.
// It returns the newly generated User ID
func (u UserRepository) CreateUser(email, hashedPassword string) (string, error) {
	newID := uuid.New().String()
	userPb := &pb.User{
		Id:           newID,
		Email:        email,
		PasswordHash: hashedPassword,
		CreatedAt:    time.Now().Unix(),
		Roles:        []string{"user"},
	}

	data, err := proto.Marshal(userPb)
	if err != nil {
		return "", fmt.Errorf("marshal failed: %w", err)
	}

	err = u.db.Update(func(txn *badger.Txn) error {
		key := []byte("user:" + email)
		if _, err = txn.Get(key); err == nil {
			return errors.ErrUserAlreadyExists
		}
		return txn.Set(key, data)
	})

	return newID, err
}

// GetUserByEmail retrieves a user from Badger and converts it to the repository.User struct.
func (u UserRepository) GetUserByEmail(email string) (User, error) {
	var userPb pb.User

	err := u.db.View(func(txn *badger.Txn) error {
		item, err := txn.Get([]byte("user:" + email))
		if err != nil {
			return err // Will be handled as ErrInvalidCredentials or ErrNotFound
		}

		return item.Value(func(val []byte) error {
			return proto.Unmarshal(val, &userPb)
		})
	})

	if err != nil {
		return User{}, err
	}

	return toUserStruct(&userPb), nil
}

func toUserStruct(pbUser *pb.User) User {
	return User{
		ID:           pbUser.Id,
		Email:        pbUser.Email,
		PasswordHash: pbUser.PasswordHash,
		Roles:        pbUser.Roles,
		CreatedAt:    time.Unix(pbUser.CreatedAt, 0).UTC(),
	}
}
