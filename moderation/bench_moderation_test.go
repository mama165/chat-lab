package moderation

import (
	"fmt"
	"github.com/mama165/sdk-go/database"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/require"
)

func Test_Moderation_Benchmark(t *testing.T) {
	req := require.New(t)
	_, log, badgerDB, blugeWriter, err := database.SetupBenchmark(database.DefaultPath)
	req.NoError(err)
	defer database.CleanupDB(badgerDB, blugeWriter)

	wordCount := 100_000

	// --- Phase 1: SEEDING ---
	startSeed := time.Now()
	wb := badgerDB.NewWriteBatch()
	for i := 0; i < wordCount; i++ {
		key := []byte(fmt.Sprintf("blacklist:word_%d", i))
		_ = wb.Set(key, nil)
	}
	err = wb.Flush()
	req.NoError(err)

	elapsed := time.Since(startSeed)

	fmt.Printf("✅ Seeding %d words: %v\n", wordCount, elapsed)
	req.Less(elapsed, 3*time.Second)

	// --- Phase 2: LOADING ---
	startLoad := time.Now()
	var words []string
	err = badgerDB.View(func(txn *badger.Txn) error {
		opts := badger.DefaultIteratorOptions
		opts.PrefetchValues = false // Crucial car les mots sont dans les clés
		it := txn.NewIterator(opts)
		defer it.Close()

		prefix := []byte("blacklist:")
		for it.Seek(prefix); it.ValidForPrefix(prefix); it.Next() {
			words = append(words, string(it.Item().Key()[len(prefix):]))
		}
		return nil
	})
	req.NoError(err)
	fmt.Printf("✅ Loading from Badger: %v\n", time.Since(startLoad))

	// --- Phase 3: BUILDING AHO-CORASICK ---
	startBuild := time.Now()
	_, err = NewModerator(words, '*', log)
	req.NoError(err)

	fmt.Printf("✅ Building AC Automaton: %v\n", time.Since(startBuild))
	fmt.Printf("\n🚀 Total startup time for moderation: %v\n", time.Since(startLoad))
}
