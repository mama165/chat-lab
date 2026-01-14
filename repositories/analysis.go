//go:generate go run go.uber.org/mock/mockgen -source=analysis.go -destination=../mocks/mock_analysis_repository.go -package=mocks
package repositories

import (
	pb "chat-lab/proto/storage"
	"fmt"
	"log/slog"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/google/uuid"
	"github.com/samber/lo"
	"google.golang.org/protobuf/proto"
	"google.golang.org/protobuf/types/known/timestamppb"
)

type IAnalysisRepository interface {
	Store(analysis Analysis) error
	Get() error
}

type AnalysisRepository struct {
	db  *badger.DB
	log *slog.Logger
}

func NewAnalysisRepository(db *badger.DB, log *slog.Logger) *AnalysisRepository {
	return &AnalysisRepository{db: db, log: log}
}

type Analysis struct {
	Id        uuid.UUID
	MessageID uuid.UUID
	Content   string
	Score     float64
	At        time.Time
	Version   uuid.UUID
}

func (a AnalysisRepository) Store(analysis Analysis) error {
	key := fmt.Sprintf("analysis:%s:%d:%s",
		analysis.Version,
		analysis.At.UnixNano(),
		analysis.MessageID.String(),
	)
	bytes, err := proto.Marshal(lo.ToPtr(fromAnalysis(analysis)))
	if err != nil {
		return err
	}
	return a.db.Update(func(txn *badger.Txn) error {
		err = txn.Set([]byte(key), bytes)
		return err
	})
}

func fromAnalysis(analysis Analysis) pb.Analysis {
	return pb.Analysis{
		Id:        analysis.Id.String(),
		MessageId: analysis.MessageID.String(),
		Content:   analysis.Content,
		Score:     analysis.Score,
		At:        timestamppb.New(analysis.At),
		Version:   analysis.Version.String(),
	}
}

func toAnalysis(analysis *pb.Analysis) (Analysis, error) {
	id, err := uuid.Parse(analysis.Id)
	if err != nil {
		return Analysis{}, err
	}
	messageID, err := uuid.Parse(analysis.MessageId)
	if err != nil {
		return Analysis{}, err
	}

	version, err := uuid.Parse(analysis.Version)
	if err != nil {
		return Analysis{}, err
	}

	return Analysis{
		Id:        id,
		MessageID: messageID,
		Content:   analysis.Content,
		Score:     analysis.Score,
		At:        analysis.At.AsTime(),
		Version:   version,
	}, nil
}

func (a AnalysisRepository) Get() error {
	//TODO implement me
	panic("implement me")
}
