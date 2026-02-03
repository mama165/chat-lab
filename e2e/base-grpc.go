package e2e

import (
	"context"
	"fmt"
	"strings"
	"testing"
	"time"

	"github.com/gookit/color"
	"github.com/stretchr/testify/suite"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/grpc/status"
	"google.golang.org/protobuf/encoding/protojson"
	"google.golang.org/protobuf/proto"

	// Replace with your actual internal proto package path
	pb "chat-lab/proto/analyzer"
)

type BaseGrpcSuite struct {
	suite.Suite
	Config Config
}

// SetupSuite loads the environment configuration before running tests
func (s *BaseGrpcSuite) SetupSuite() {
	var err error
	s.Config, err = LoadConfig()
	s.Require().NoError(err)
}

// GrpcConn initializes a gRPC connection with logging, colors, and JSON debugging
func (s *BaseGrpcSuite) GrpcConn(t *testing.T, name string, addr string) *grpc.ClientConn {
	// 1. Print a colorized header for the connection step in logs
	header := fmt.Sprintf("  ====== %s ======", name)
	if s.Config.Colours {
		header = color.New(color.BgBlack, color.FgGreen).Render(header)
	}
	t.Log(header)

	// 2. Setup JSON marshaler for debugging protobuf messages
	marshaler := protojson.MarshalOptions{
		UseProtoNames:   true,
		Multiline:       true,
		EmitUnpopulated: true,
	}

	// 3. Create the client with a Unary Interceptor for logging
	conn, err := grpc.NewClient(addr,
		grpc.WithTransportCredentials(insecure.NewCredentials()),
		grpc.WithUnaryInterceptor(func(ctx context.Context, method string, req, reply interface{}, cc *grpc.ClientConn, invoker grpc.UnaryInvoker, opts ...grpc.CallOption) error {
			start := time.Now()
			err := invoker(ctx, method, req, reply, cc, opts...)

			logBuilder := strings.Builder{}
			fmt.Fprintf(&logBuilder, "GRPC %s [%s] in %v", method, status.Code(err), time.Since(start))

			// Log full JSON request/response bodies if E2E_DEBUG_JSON is enabled
			if s.Config.DebugJSON {
				fmt.Fprintln(&logBuilder, "\nREQUEST:")
				fmt.Fprintln(&logBuilder, marshaler.Format(req.(proto.Message)))
				if err != nil {
					fmt.Fprintln(&logBuilder, "ERROR:", err)
				} else {
					fmt.Fprintln(&logBuilder, "RESPONSE:")
					fmt.Fprintln(&logBuilder, marshaler.Format(reply.(proto.Message)))
				}
			}
			t.Log(logBuilder.String())
			return err
		}),
	)
	s.Require().NoError(err, "Failed to connect to gRPC server at "+addr)
	return conn
}

// WithScanner provides a FileDownloaderService client within a contextual test step
func (s *BaseGrpcSuite) WithScanner(name string, fn func(ctx context.Context, client pb.FileDownloaderServiceClient)) {
	conn := s.GrpcConn(s.T(), name, s.Config.ScannerAddr)
	defer conn.Close()

	client := pb.NewFileDownloaderServiceClient(conn)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	fn(ctx, client)
}

// WithMaster provides a Master service client (Placeholder for future Master proto)
func (s *BaseGrpcSuite) WithMaster(name string, fn func(ctx context.Context, client pb.FileDownloaderServiceClient)) {
	conn := s.GrpcConn(s.T(), name, s.Config.MasterAddr)
	defer conn.Close()

	// Assuming Master uses the same or similar service client for now
	client := pb.NewFileDownloaderServiceClient(conn)
	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	fn(ctx, client)
}
