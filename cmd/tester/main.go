package main

import (
	pb "chat-lab/proto/analyzer"
	"context"
	"fmt"
	"log"
	"os"
	"time"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
	"google.golang.org/protobuf/types/known/timestamppb"
)

func main() {
	conn, err := grpc.Dial("localhost:8080", grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Échec connexion Master: %v", err)
	}
	defer conn.Close()

	client := pb.NewFileAnalyzerServiceClient(conn)

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()

	stream, err := client.Analyze(ctx)
	if err != nil {
		log.Fatalf("Impossible d'ouvrir le stream: %v", err)
	}

	filePath := "/Users/maelnana/Desktop/develop/mama165/chat-lab/test_audio.aiff"
	info, err := os.Stat(filePath)
	if err != nil {
		log.Fatalf("Fichier introuvable: %v", err)
	}

	// Optionnel: lire les premiers octets pour les magic_bytes (sniffing)
	f, _ := os.Open(filePath)
	magic := make([]byte, 64)
	_, _ = f.Read(magic)
	f.Close()

	// 4. Envoi de la requête dans le stream
	fmt.Printf("🚀 Envoi de %s via le stream gRPC...\n", filePath)

	req := &pb.FileAnalyzerRequest{
		Path:       filePath,
		DriveId:    "USB-DRIVE-001",
		Size:       uint64(info.Size()),
		MimeType:   "audio/mpeg",
		MagicBytes: magic,
		ScannedAt:  timestamppb.Now(),
		SourceType: pb.SourceType_LOCAL_FIXED,
	}

	fmt.Println("Appel de :", pb.FileAnalyzerService_Analyze_FullMethodName)

	if err := stream.Send(req); err != nil {
		log.Fatalf("Échec de l'envoi du message: %v", err)
	}

	// 5. Fermeture du flux et récupération de la réponse globale
	reply, err := stream.CloseAndRecv()
	if err != nil {
		log.Fatalf("Erreur lors de la réception du résumé: %v", err)
	}

	fmt.Println("\n--- ✅ Rapport du Master ---")
	fmt.Printf("Fichiers reçus : %d\n", reply.FilesReceived)
	fmt.Printf("Total octets : %d\n", reply.BytesProcessed)
	fmt.Printf("Terminé à : %s\n", reply.EndedAt.AsTime().Format(time.RFC850))
}
