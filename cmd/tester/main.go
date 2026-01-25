package main

import (
	"chat-lab/domain/specialist"
	client2 "chat-lab/grpc/client"
	pb "chat-lab/proto/analysis"
	"context"
	"fmt"
	"log"
	"os"

	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials/insecure"
)

func main() {
	// 1. Connexion au serveur Python (port 50051)
	// On utilise insecure car on n'a pas encore configuré TLS/Certificats
	conn, err := grpc.Dial("localhost:50051", grpc.WithTransportCredentials(insecure.NewCredentials()))
	if err != nil {
		log.Fatalf("Impossible de se connecter: %v", err)
	}
	defer conn.Close()

	client := pb.NewSpecialistServiceClient(conn)

	stream, err := client.AnalyzeStream(context.Background())
	if err != nil {
		log.Fatalf("Erreur ouverture stream: %v", err)
	}

	// 1. Lire le fichier PDF (mets un petit PDF dans ton dossier root pour tester)
	fileContent, err := os.ReadFile("mon_test.pdf")
	if err != nil {
		log.Fatalf("Impossible de lire le PDF: %v", err)
	}

	// 2. Envoyer les metadata d'abord
	err = stream.Send(&pb.SpecialistRequest{
		Entry: &pb.SpecialistRequest_Metadata{
			Metadata: &pb.Metadata{FileName: "mon_test.pdf"},
		},
	})
	if err != nil {
		log.Fatalf("Error : %v", err)
	}

	// 3. Envoyer le contenu par chunks de 32KB
	const chunkSize = 32 * 1024
	for i := 0; i < len(fileContent); i += chunkSize {
		end := i + chunkSize
		if end > len(fileContent) {
			end = len(fileContent)
		}

		err = stream.Send(&pb.SpecialistRequest{
			Entry: &pb.SpecialistRequest_Chunk{
				Chunk: fileContent[i:end],
			},
		})
		if err != nil {
			log.Fatalf("Error : %v", err)
		}
	}

	// 4. Récupérer la réponse et l'afficher
	reply, _ := stream.CloseAndRecv()
	res := client2.ToResponse(reply)

	fmt.Printf("\n--- 🔍 [Extraction Results] ---\n")

	switch res := res.OneOf.(type) {
	case specialist.DocumentData:
		fmt.Printf("📂 Filename: %s\n", res.Title)
		fmt.Printf("👤 Author:   %s\n", res.Author)
		fmt.Printf("📄 Pages:    %d\n", res.PageCount)
		fmt.Printf("🌍 Lang:     %s\n", res.Language)

		if len(res.Pages) > 0 {
			fmt.Println("\n--- 📝 [First Page Preview] ---")
			content := res.Pages[0].Content
			if len(content) > 300 {
				fmt.Printf("%s [...]\n", content[:300])
			} else {
				fmt.Println(content)
			}
		}

	case specialist.Score:
		fmt.Printf("📊 Score: %.2f | Label: %s\n", res.Score, res.Label)

	default:
		fmt.Println("⚠️ Unknown response type received")
	}
}
