package catalog

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

// stubEncoder writes a small program standing for the model: it answers each
// line with a vector made from the length of the text, fails on "boom", stays
// silent on "sleep" and answers nonsense on "garbage".
func stubEncoder(t *testing.T) string {
	t.Helper()
	if _, err := exec.LookPath("python3"); err != nil {
		t.Skip("python3 is not installed")
	}
	script := filepath.Join(t.TempDir(), "stub.py")
	require.NoError(t, os.WriteFile(script, []byte(`
import json, sys, time
for line in sys.stdin:
    text = json.loads(line)["text"]
    if text == "boom":
        print(json.dumps({"error": "cannot encode"}), flush=True)
    elif text == "sleep":
        time.sleep(30)
    elif text == "garbage":
        print("not json", flush=True)
    elif text == "empty":
        print(json.dumps({"vector": []}), flush=True)
    else:
        print(json.dumps({"vector": [float(len(text)), 1.0]}), flush=True)
`), 0o600))
	return script
}

func TestProcessEncoder_AnswersInOrder(t *testing.T) {
	encoder, err := StartProcessEncoder("python3", stubEncoder(t))
	require.NoError(t, err)
	defer encoder.Close()

	for _, text := range []string{"a", "une rose", "été « accentué »\nsur deux lignes"} {
		vector, err := encoder.EncodeText(context.Background(), text)
		require.NoError(t, err)
		// The stub counts characters, not bytes.
		require.Equal(t, []float32{float32(len([]rune(text))), 1}, vector)
	}
}

func TestProcessEncoder_ATextThatFailsDoesNotStopIt(t *testing.T) {
	encoder, err := StartProcessEncoder("python3", stubEncoder(t))
	require.NoError(t, err)
	defer encoder.Close()

	_, err = encoder.EncodeText(context.Background(), "boom")
	require.ErrorContains(t, err, "cannot encode")
	_, err = encoder.EncodeText(context.Background(), "empty")
	require.ErrorContains(t, err, "no vector")

	vector, err := encoder.EncodeText(context.Background(), "abc")
	require.NoError(t, err)
	require.Equal(t, []float32{3, 1}, vector)
}

func TestProcessEncoder_StopsWhenNobodyWaits(t *testing.T) {
	encoder, err := StartProcessEncoder("python3", stubEncoder(t))
	require.NoError(t, err)
	defer encoder.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 200*time.Millisecond)
	defer cancel()

	started := time.Now()
	_, err = encoder.EncodeText(ctx, "sleep")

	require.ErrorIs(t, err, context.DeadlineExceeded)
	require.Less(t, time.Since(started), 5*time.Second)
	// A late answer would be given to the wrong question: the program is gone.
	_, err = encoder.EncodeText(context.Background(), "abc")
	require.ErrorIs(t, err, context.DeadlineExceeded)
}

func TestProcessEncoder_BrokenProgram(t *testing.T) {
	encoder, err := StartProcessEncoder("python3", stubEncoder(t))
	require.NoError(t, err)
	_, err = encoder.EncodeText(context.Background(), "garbage")
	require.ErrorContains(t, err, "unreadable answer")
	require.NoError(t, encoder.Close())

	_, err = StartProcessEncoder(filepath.Join(t.TempDir(), "absent"))
	require.Error(t, err)

	// A program that exits at once: the first question finds it gone.
	gone, err := StartProcessEncoder("python3", "-c", "pass")
	require.NoError(t, err)
	defer gone.Close()
	_, err = gone.EncodeText(context.Background(), "abc")
	require.ErrorContains(t, err, "stopped answering")
}

func TestProcessEncoder_SearchFallsBackOnWordsWhenItFails(t *testing.T) {
	c := openLibrary(t)
	encoder, err := StartProcessEncoder("python3", stubEncoder(t))
	require.NoError(t, err)
	require.NoError(t, encoder.Close())

	_, err = c.Search(context.Background(), ask(t, c, "une photo de cuisine").Plan, Options{Encoder: encoder, Now: testNow})

	// A search cannot silently pretend it compared pictures.
	require.Error(t, err)
}
