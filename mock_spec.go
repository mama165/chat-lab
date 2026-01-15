package main

import (
	"flag"
	"fmt"
	"log"
	"net"

	"google.golang.org/grpc"
)

func main() {
	// flag.String gère automatiquement le fait que --port soit en os.Args[1]
	port := flag.String("port", "8081", "port to listen on")
	flag.Parse()

	address := ":" + *port
	lis, err := net.Listen("tcp", address)
	if err != nil {
		log.Fatalf("Failed to listen: %v", err)
	}

	s := grpc.NewServer()
	fmt.Printf("Mock Specialist listening on %s\n", address)

	if err := s.Serve(lis); err != nil {
		log.Fatalf("Failed to serve: %v", err)
	}
}
