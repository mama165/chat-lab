package repositories

import (
	pb "chat-lab/proto/storage"
	"fmt"

	"github.com/google/uuid"
	"github.com/stretchr/testify/require"
	"google.golang.org/protobuf/types/known/timestamppb"

	"log/slog"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"google.golang.org/protobuf/proto"
)

func Test_MessageHistory_Performance(t *testing.T) {
	req := require.New(t)
	path := t.TempDir()
	db, err := badger.Open(badger.DefaultOptions(path).
		WithLoggingLevel(badger.ERROR).
		WithValueLogFileSize(16 << 20))
	req.NoError(err)
	defer db.Close()

	log := slog.Default()
	limit := 50
	repo := NewMessageRepository(db, log, &limit)

	totalMessages := 1_000_000
	targetRoom := 42

	// --- Phase 1: SEEDING 1 MILLION MESSAGES ---
	// On utilise Protobuf pour simuler le format réel en base
	fmt.Printf("Starting seeding of %d messages...\n", totalMessages)
	startSeed := time.Now()
	wb := db.NewWriteBatch()

	for i := 0; i < totalMessages; i++ {
		roomID := i % 100                                        // Distribution sur 100 rooms
		at := time.Now().Add(time.Duration(i) * time.Nanosecond) // Nanosecondes pour éviter les collisions de clés

		author := fmt.Sprintf("user_%d", i%500)
		content := "Hello world, this is a performance test for Chat-Lab!"

		// 1. On crée la clé au format réel du repository
		// msg:{room_id}:{timestamp}:{user_id}
		key := fmt.Sprintf("msg:%d:%d:%s", roomID, at.UnixNano(), author)

		// 2. On sérialise en Protobuf comme le fait ton code de prod
		pbMsg := pb.Message{
			Id:      uuid.NewString(),
			Room:    int64(roomID),
			Author:  author,
			Content: content,
			At:      timestamppb.New(at),
		}
		bytes, _ := proto.Marshal(&pbMsg)

		// 3. Ajout au batch
		_ = wb.Set([]byte(key), bytes)

		if i%200_000 == 0 && i > 0 {
			fmt.Printf("  -> Inserted %d messages...\n", i)
		}
	}

	err = wb.Flush()
	req.NoError(err)

	fmt.Printf("✅ Seeded %d messages in %v\n", totalMessages, time.Since(startSeed))

	// --- RECOVERY OF 50 MESSAGES IN ROOM 42 ---
	fmt.Printf("Retrieving last %d messages for Room %d...\n", limit, targetRoom)
	startGet := time.Now()

	messages, _, err := repo.GetMessages(targetRoom, nil)
	req.NoError(err)

	duration := time.Since(startGet)
	fmt.Printf("✅ Retrieved %d messages for Room %d in %v\n", len(messages), targetRoom, duration)

	// --- VERIFICATION ---
	req.NotEmpty(messages)
}
