package main

import (
	v1 "chat-lab/proto/chat"
	"context"
	"fmt"
	"github.com/mama165/sdk-go/logs"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/Netflix/go-env"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

// Exit codes for the client application.
const (
	exitOK      = 0
	exitRuntime = 1
	exitConfig  = 2
)

// Config defines the client-side environment variables.
type Config struct {
	ServerAddress string `env:"CHAT_SERVER_ADDR,default=localhost:8080"`
	DefaultRoomID int    `env:"CHAT_ROOM_ID,default=1"`
	LogLevel      string `env:"LOG_LEVEL,required=true"`
}

func main() {
	// The main function manages the OS exit code based on run()'s return.
	code, err := run()
	if err != nil {
		fmt.Fprintf(os.Stderr, "Client error: %v", err)
	}
	os.Exit(code)
}

// run handles the gRPC client lifecycle, configuration loading, and message streaming.
// This pattern ensures clean resource management and error propagation.
func run() (int, error) {
	// 1. Load configuration from environment variables.
	var config Config
	if _, err := env.UnmarshalFromEnviron(&config); err != nil {
		return exitConfig, fmt.Errorf("config error: %w", err)
	}

	log := logs.GetLoggerFromString(config.LogLevel)

	// 2. Setup context to handle termination signals (Ctrl+C).
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	// 3. Establish connection to the Chat-Lab server.
	// We use the address provided in the configuration.
	conn, err := grpc.NewClient(config.ServerAddress, grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		return exitRuntime, fmt.Errorf("could not connect to server at %s: %w", config.ServerAddress, err)
	}
	// Defer ensures the connection is closed even if the stream fails later.
	defer func() {
		log.Info("Closing connection...")
		_ = conn.Close()
	}()

	client := v1.NewChatServiceClient(conn)

	// 4. Initiate the bidirectional stream.
	stream, err := client.Connect(ctx, &v1.ConnectRequest{RoomId: int64(config.DefaultRoomID)})
	if err != nil {
		return exitRuntime, fmt.Errorf("failed to open stream: %w", err)
	}

	log.Info(">>> Connected to %s! Listening Room %d (Ctrl+C to quit)...",
		config.ServerAddress, config.DefaultRoomID)

	// 5. Message reception loop.
	// This loop runs until the context is canceled or the server closes the connection.
	for {
		select {
		case <-ctx.Done():
			log.Info("Stopping client...")
			return exitOK, nil
		default:
			resp, err := stream.Recv()
			if err != nil {
				// Normal exit if the user triggered a shutdown.
				if ctx.Err() != nil {
					return exitOK, nil
				}
				return exitRuntime, fmt.Errorf("stream error: %w", err)
			}

			// Display the received message.
			msg := resp.GetMessage()
			log.Info(fmt.Sprintf("[%s] %s: %s",
				msg.CreatedAt.AsTime().Format(time.TimeOnly),
				msg.Author,
				msg.Content,
			))
		}
	}
}
