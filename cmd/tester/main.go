package main

import (
	"chat-lab/domain/specialist"
	client2 "chat-lab/grpc/client"
	pb "chat-lab/proto/analysis"
	"context"
	"fmt"
	"log"
	"time"

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

	// 2. Initialisation du stream gRPC
	stream, err := client.AnalyzeStream(context.Background())
	if err != nil {
		log.Fatalf("Erreur ouverture stream: %v", err)
	}

	// 3. Envoi des métadonnées (Entry: Metadata)
	err = stream.Send(&pb.SpecialistRequest{
		Entry: &pb.SpecialistRequest_Metadata{
			Metadata: &pb.Metadata{
				MessageId: "msg-123",
				FileName:  "test_performance.txt",
				MimeType:  "text/plain",
			},
		},
	})
	if err != nil {
		log.Fatalf("Erreur envoi metadata: %v", err)
	}

	// 4. Envoi de chunks de simulation (Entry: Chunk)
	for i := 1; i <= 3; i++ {
		fmt.Printf("Envoi du chunk %d...\n", i)
		err = stream.Send(&pb.SpecialistRequest{
			Entry: &pb.SpecialistRequest_Chunk{
				Chunk: []byte(fmt.Sprintf("Contenu du morceau numéro %d. ", i)),
			},
		})
		if err != nil {
			log.Fatalf("Erreur envoi chunk: %v", err)
		}
		time.Sleep(200 * time.Millisecond)
	}

	// 5. Fermeture du stream et récupération de la réponse brute
	reply, err := stream.CloseAndRecv()
	if err != nil {
		log.Fatalf("Erreur réception résultat: %v", err)
	}

	// 6. Conversion vers ton modèle de domaine (utilisant ToResponse de ton package client)
	finalResponse := client2.ToResponse(reply)

	// 7. Affichage propre selon le type reçu (Pattern Matching sur le OneOf)
	fmt.Printf("\n--- [Résultat du Spécialiste] ---\n")

	switch res := finalResponse.OneOf.(type) {
	case specialist.Score:
		fmt.Printf("Type: SCORE\n")
		fmt.Printf("Label: %s\n", res.Label)
		fmt.Printf("Score: %.2f/1.0\n", res.Score)
	case specialist.DocumentData:
		fmt.Printf("Type: DOCUMENT\n")
		fmt.Printf("Titre: %s | Auteur: %s | Langue: %s\n", res.Title, res.Author, res.Language)
		fmt.Printf("Nombre de pages: %d\n", res.PageCount)
	default:
		fmt.Printf("Type de réponse inconnu ou vide\n")
	}
}
