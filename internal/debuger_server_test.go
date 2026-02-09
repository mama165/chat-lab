package internal

import (
	"fmt"
	"io"
	"net/http"
	"testing"
	"time"

	"github.com/dgraph-io/badger/v4"
	"github.com/stretchr/testify/assert"
	"github.com/stretchr/testify/require"
)

// TestDefaultMapper checks if the key-to-row parsing logic works as expected.
func TestDefaultMapper(t *testing.T) {
	// GIVEN: A standard Badger key for Chat-Lab
	ts := time.Now().UnixNano()
	key := fmt.Sprintf("analysis:room-42:%d:uuid-12345678-90", ts)
	val := []byte("some-binary-data")

	// WHEN: Mapping the row
	row := DefaultMapper(key, val)

	// THEN: Metadata should be correctly extracted
	assert.Equal(t, "room-42", row.Namespace)
	assert.Equal(t, "uuid-123", row.EntityID) // Truncated to 8
	assert.Equal(t, "Size: 16 bytes", row.Detail)
	assert.Equal(t, time.Unix(0, ts).Format("15:04:05"), row.Timestamp)
}

// TestDebugServer_Routing validates that the HTTP server responds and renders items.
func TestDebugServer_Routing(t *testing.T) {
	// GIVEN: A temporary Badger DB
	opts := badger.DefaultOptions("").WithInMemory(true).WithLogger(nil)
	db, err := badger.Open(opts)
	require.NoError(t, err)
	defer db.Close()

	// Fill with one dummy record
	err = db.Update(func(txn *badger.Txn) error {
		return txn.Set([]byte("analysis:test:123:id"), []byte("data"))
	})
	require.NoError(t, err)

	// WHEN: Starting the server on a random port
	port := 9999
	StartDebugServer(db, port, "/inspect", nil, nil)

	// Give it a few ms to boot
	time.Sleep(100 * time.Millisecond)

	// THEN: It should serve the HTML page
	resp, err := http.Get(fmt.Sprintf("http://localhost:%d/inspect?prefix=analysis:", port))
	require.NoError(t, err)
	defer resp.Body.Close()

	assert.Equal(t, http.StatusOK, resp.StatusCode)

	body, _ := io.ReadAll(resp.Body)
	assert.Contains(t, string(body), "CHAT-LAB INSPECTOR")
	assert.Contains(t, string(body), "analysis:test:123:id")
}

// TestResumeSignal validates that the Resume endpoint unblocks the Wait function.
func TestResumeSignal(t *testing.T) {
	// GIVEN: A running wait state (simulated)
	go func() {
		time.Sleep(200 * time.Millisecond)
		// Simulating a click on "Resume" button
		_, _ = http.Get(fmt.Sprintf("http://localhost:%d/resume", currentPort))
	}()

	// WHEN: Wait is called
	start := time.Now()
	Wait("test:")
	duration := time.Since(start)

	// THEN: It should have blocked for at least 200ms
	assert.GreaterOrEqual(t, duration.Milliseconds(), int64(200))
}
