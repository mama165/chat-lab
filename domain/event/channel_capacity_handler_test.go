package event

import (
	"bytes"
	"chat-lab/observability"
	"log/slog"
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func handleCapacity(threshold int, name string, capacity, length int) string {
	var logs bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&logs, &slog.HandlerOptions{Level: slog.LevelWarn}))
	handler := NewChannelCapacityHandler(logger, threshold, observability.NewMonitoringManager(logger))

	handler.Handle(Event{
		Type:      ChannelCapacityType,
		CreatedAt: time.Now(),
		Payload:   ChannelCapacity{ChannelName: name, Capacity: capacity, Length: length},
	})
	return logs.String()
}

func TestChannelCapacityHandler_EmptyQueueNeverWarns(t *testing.T) {
	// The threshold is larger than the queue: every slot is "low", yet nothing is queued.
	require.Empty(t, handleCapacity(50, "FileChan", 10, 0))
}

func TestChannelCapacityHandler_WarnsWhenNearlyFull(t *testing.T) {
	out := handleCapacity(2, "FileChan", 10, 8)
	require.Contains(t, out, "FileChan", "the warning must say which channel is concerned")
	require.Contains(t, out, "nearly full")
}

func TestChannelCapacityHandler_WarnsWhenFull(t *testing.T) {
	require.Contains(t, handleCapacity(2, "EventChan", 10, 10), "EventChan")
}

func TestChannelCapacityHandler_QuietWhileThereIsRoom(t *testing.T) {
	require.Empty(t, handleCapacity(2, "FileChan", 10, 5))
}

func TestChannelCapacityHandler_IgnoresUnbufferedChannels(t *testing.T) {
	require.Empty(t, handleCapacity(2, "Unbuffered", 0, 0))
}
