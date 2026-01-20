package main

import (
	"chat-lab/infrastructure/storage"
	pb "chat-lab/proto/storage"
	"flag"
	"fmt"
	"log"
	"os"
	"strings"

	"github.com/dgraph-io/badger/v4"
	"github.com/mama165/sdk-go/database"
	"github.com/olekukonko/tablewriter"
	"google.golang.org/protobuf/proto"
)

func main() {
	dbPath := flag.String("db", database.DefaultPath, "Path to badger DB")
	// Par défaut on cherche "analysis:" pour éviter de percuter les index idx:
	prefix := flag.String("prefix", "analysis:", "Prefix to scan")
	flag.Parse()

	db, err := badger.Open(badger.DefaultOptions(*dbPath).WithLoggingLevel(badger.ERROR))
	if err != nil {
		log.Fatal("Error while opening Badger: ", err)
	}
	defer db.Close()

	table := tablewriter.NewWriter(os.Stdout)
	table.SetHeader([]string{"Type", "Timestamp", "Entity ID", "Namespace", "Detail", "Scores"})

	// Style minimaliste et propre
	table.SetAutoWrapText(false)
	table.SetAutoFormatHeaders(true)
	table.SetHeaderAlignment(tablewriter.ALIGN_LEFT)
	table.SetAlignment(tablewriter.ALIGN_LEFT)
	table.SetCenterSeparator("")
	table.SetColumnSeparator("")
	table.SetRowSeparator("")
	table.SetHeaderLine(false)
	table.SetBorder(false)
	table.SetTablePadding("\t")

	err = db.View(func(txn *badger.Txn) error {
		it := txn.NewIterator(badger.DefaultIteratorOptions)
		defer it.Close()

		prefixBytes := []byte(*prefix)
		for it.Seek(prefixBytes); it.ValidForPrefix(prefixBytes); it.Next() {
			item := it.Item()

			// Sécurité : on ignore explicitement les index secondaires
			if strings.HasPrefix(string(item.Key()), "idx:") {
				continue
			}

			err := item.Value(func(v []byte) error {
				var p pb.Analysis
				if err := proto.Unmarshal(v, &p); err != nil {
					// Au lieu de stopper tout le script, on log l'erreur et on continue
					fmt.Printf("Error unmarshaling key %s: %v\n", string(item.Key()), err)
					return nil
				}

				analysis, err := storage.ToAnalysis(&p)
				if err != nil {
					return err
				}

				payloadType := "BASE"
				detail := analysis.Summary

				// Détection du type de payload (gestion pointers/values)
				if analysis.Payload != nil {
					switch analysis.Payload.(type) {
					case storage.TextContent, *storage.TextContent:
						payloadType = "CHAT"
					case storage.FileDetails, *storage.FileDetails:
						payloadType = "FILE"
					case storage.AudioDetails, *storage.AudioDetails:
						payloadType = "AUDIO"
					}
				}

				scores := ""
				for k, v := range p.Scores {
					scores += fmt.Sprintf("%s:%.2f ", k, v)
				}

				// On affiche les 8 premiers caractères de l'EntityId pour la lisibilité
				displayID := p.EntityId
				if len(displayID) > 8 {
					displayID = displayID[:8]
				}

				table.Append([]string{
					payloadType,
					analysis.At.Format("15:04:05"),
					displayID,
					p.Namespace,
					detail,
					scores,
				})
				return nil
			})
			if err != nil {
				return err
			}
		}
		return nil
	})

	if err != nil {
		log.Fatal(err)
	}

	table.Render()
}
