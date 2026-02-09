package internal

import (
	"embed"
	"fmt"
	"html/template"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/dgraph-io/badger/v4"
)

//go:embed inspect.html
var templatesFS embed.FS

var (
	resumeChan  = make(chan struct{}, 1)
	currentPort int
)

type InspectRow struct {
	Key       string
	Type      string
	Timestamp string
	EntityID  string
	Namespace string
	Detail    string
	Scores    string
}

type RowMapper func(key string, val []byte) InspectRow
type StatsProvider func() map[string]any

type PageData struct {
	Prefix string
	Items  []InspectRow
	Stats  map[string]any
}

func Inspect(db *badger.DB, port int, endpoint string, mapper RowMapper, statsProvider StatsProvider, prefix string, fn func()) {
	StartDebugServer(db, port, endpoint, mapper, statsProvider)

	if fn != nil {
		fn()
	}

	Wait(prefix)
}

func StartDebugServer(db *badger.DB, port int, endpoint string, mapper RowMapper, statsProvider StatsProvider) {
	currentPort = port
	mux := http.NewServeMux()
	tmpl := template.Must(template.ParseFS(templatesFS, "inspect.html"))

	if mapper == nil {
		mapper = DefaultMapper
	}

	mux.HandleFunc(endpoint, func(w http.ResponseWriter, r *http.Request) {
		prefix := r.URL.Query().Get("prefix")
		if prefix == "" {
			prefix = "analysis:"
		}

		data := PageData{
			Prefix: prefix,
			Stats:  make(map[string]any),
		}

		// Récupération des statistiques dynamiques pour le dashboard
		if statsProvider != nil {
			data.Stats = statsProvider()
		}

		_ = db.View(func(txn *badger.Txn) error {
			it := txn.NewIterator(badger.DefaultIteratorOptions)
			defer it.Close()
			for it.Seek([]byte(prefix)); it.ValidForPrefix([]byte(prefix)); it.Next() {
				item := it.Item()
				_ = item.Value(func(val []byte) error {
					data.Items = append(data.Items, mapper(string(item.Key()), val))
					return nil
				})
			}
			return nil
		})

		w.Header().Set("Content-Type", "text/html; charset=utf-8")
		_ = tmpl.Execute(w, data)
	})

	mux.HandleFunc("/resume", func(w http.ResponseWriter, r *http.Request) {
		select {
		case <-resumeChan:
		default:
		}
		resumeChan <- struct{}{}
		fmt.Fprint(w, "RESUMED")
	})

	go func() {
		// Écoute sur toutes les interfaces pour permettre l'accès réseau
		_ = http.ListenAndServe(fmt.Sprintf("0.0.0.0:%d", port), mux)
	}()
}

func Wait(prefix string) {
	url := fmt.Sprintf("http://localhost:%d/inspect?prefix=%s", currentPort, prefix)
	fmt.Printf("\n--- TEST PAUSED ---\n\n%s\n\n-------------------\n", url)
	<-resumeChan
}

func DefaultMapper(key string, val []byte) InspectRow {
	parts := strings.Split(key, ":")
	row := InspectRow{
		Key:       key,
		Type:      "RAW",
		Timestamp: "--:--:--",
		EntityID:  "--------",
		Namespace: "default",
		Detail:    "Size: " + strconv.Itoa(len(val)) + " bytes",
		Scores:    "-",
	}

	if len(parts) >= 4 {
		row.Namespace = parts[1]
		if tsNano, err := strconv.ParseInt(parts[2], 10, 64); err == nil {
			row.Timestamp = time.Unix(0, tsNano).Format("15:04:05")
		}
		row.EntityID = parts[3]
		if len(row.EntityID) > 8 {
			row.EntityID = row.EntityID[:8]
		}
	}
	return row
}
