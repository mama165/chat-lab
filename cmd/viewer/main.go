package main

import (
	"chat-lab/infrastructure/storage"
	"chat-lab/internal"
	pb4 "chat-lab/proto/storage"
	"fmt"
	"log"
	"time"

	"github.com/Netflix/go-env"
	"github.com/dgraph-io/badger/v4"
	"github.com/joho/godotenv"
	"google.golang.org/protobuf/proto"
)

func main() {
	// 1. Load config
	_ = godotenv.Load()
	var config internal.Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		log.Fatalf("Config error: %v", err)
	}

	// 2. Open Badger in Read-Only mode
	// Note: BypassLockGuard allows opening if another process (Master) holds the lock
	opts := badger.DefaultOptions(config.BadgerFilepath).
		WithReadOnly(true).
		WithBypassLockGuard(true).
		WithLoggingLevel(badger.WARNING)

	db, err := badger.Open(opts)
	if err != nil {
		log.Fatalf("Failed to open database: %v", err)
	}
	defer db.Close()

	// 3. Start Debug Server Only
	// We provide an empty stats provider since the orchestrator isn't running here
	emptyStats := func() map[string]any {
		return map[string]any{
			"Status": "Viewer Mode (Read-Only)",
			"Time":   time.Now().Format(time.RFC822),
		}
	}

	fmt.Printf("🌐 Viewer started at http://localhost:%d/inspect\n", config.DebugPort)

	// We reuse your AnalysisMapper from the Master
	internal.StartDebugServer(db, config.DebugPort, "/inspect", AnalysisMapper, emptyStats)
}

// Copy of your AnalysisMapper to keep the viewer independent
func AnalysisMapper(key string, val []byte) internal.InspectRow {
	row := internal.DefaultMapper(key, val)
	var p pb4.Analysis
	if err := proto.Unmarshal(val, &p); err != nil {
		return row
	}
	analysis, _ := storage.ToAnalysis(&p)

	row.Type = "BASE"
	row.Detail = analysis.Summary

	if analysis.Payload != nil {
		switch payload := analysis.Payload.(type) {
		case storage.AudioDetails, *storage.AudioDetails:
			row.Type = "AUDIO"
		case storage.FileDetails, *storage.FileDetails:
			row.Type = "FILE"
			if f, ok := payload.(storage.FileDetails); ok {
				row.Detail = f.Content
			}
		}
	}

	for k, v := range analysis.Scores {
		row.Scores += fmt.Sprintf("%s:%.2f ", k, v)
	}
	return row
}
