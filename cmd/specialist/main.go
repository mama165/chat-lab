package main

import (
	"chat-lab/grpc/server"
	pb "chat-lab/proto/analysis"
	"flag"
	"fmt"
	"log"
	"log/slog"
	"net"

	"github.com/mama165/sdk-go/logs"
	"github.com/samber/lo"
	"google.golang.org/grpc"
)

func main() {
	// Parsing flags passed by the Master's StartSpecialist
	id := flag.String("id", "unknown", "Specialist ID")
	port := flag.Int("port", 50051, "gRPC port")
	kind := flag.String("type", "generic", "Type of analysis")
	level := flag.String("level", "INFO", "Log Level")
	flag.Parse()

	lis, err := net.Listen("tcp", fmt.Sprintf(":%d", *port))
	if err != nil {
		log.Fatalf("failed to listen: %v", err)
	}

	logger := logs.GetLoggerFromString(lo.FromPtr(level))
	s := grpc.NewServer()

	pb.RegisterSpecialistServiceServer(s, server.NewSpecialistServer(*id, *kind, logger))

	slog.Info("Specialist starting", "id", *id, "kind", *kind, "port", *port)
	if err := s.Serve(lis); err != nil {
		log.Fatalf("failed to serve: %v", err)
	}
}
