package catalog

import (
	"chat-lab/domain/query"
	"context"
	"encoding/json"
	"fmt"
	"strconv"
	"strings"
	"sync"

	"github.com/blugelabs/bluge"
	"github.com/blugelabs/bluge/analysis"
	"github.com/blugelabs/bluge/analysis/lang/fr"
	"github.com/blugelabs/bluge/analysis/token"
	"github.com/blugelabs/bluge/analysis/tokenizer"
)

// Catalog holds the entries of the library and answers search plans.
//
// The text of the files lives in a full-text index on disk. The facts about
// each file (kind, dates, place, counts) and the vectors are also kept in
// memory: at the size of a personal library this is a few megabytes, and it
// lets every exact criterion be checked in plain code instead of being
// translated into index queries.
type Catalog struct {
	mu      sync.RWMutex
	dir     string
	writer  *bluge.Writer
	items   map[string]*Item
	vectors map[string][][]float32
	// dirty is set when vectors changed since they were last saved.
	dirty bool
}

// Fields of the full-text index.
const (
	fieldID   = "id"
	fieldPage = "page"
	fieldName = "name"
	fieldBody = "body"
	fieldMeta = "meta"
)

// french is the analyzer used for file names and text, at indexing and at
// search time: French stop words and stems, then accents removed, so that
// "électricité", "electricite" and "Électricités" meet.
var french = &analysis.Analyzer{
	Tokenizer: tokenizer.NewUnicodeTokenizer(),
	TokenFilters: []analysis.TokenFilter{
		token.NewLowerCaseFilter(),
		fr.ElisionFilter(),
		fr.StopWordsFilter(),
		fr.LightStemmerFilter(),
		foldFilter{},
	},
}

// foldFilter removes accents from the words of the index.
type foldFilter struct{}

func (foldFilter) Filter(input analysis.TokenStream) analysis.TokenStream {
	for _, tok := range input {
		tok.Term = []byte(strings.ReplaceAll(query.Fold(string(tok.Term)), " ", ""))
	}
	return input
}

// Open opens the catalog stored in dir, creating it if needed. An empty dir
// gives a catalog in memory, for tests.
func Open(dir string) (*Catalog, error) {
	config := bluge.InMemoryOnlyConfig()
	if dir != "" {
		config = bluge.DefaultConfig(dir)
	}
	writer, err := bluge.OpenWriter(config)
	if err != nil {
		return nil, fmt.Errorf("catalog: opening index: %w", err)
	}
	c := &Catalog{dir: dir, writer: writer, items: map[string]*Item{}, vectors: map[string][][]float32{}}
	if dir != "" {
		if c.vectors, err = loadVectors(dir); err != nil {
			writer.Close()
			return nil, err
		}
	}
	if err := c.loadItems(); err != nil {
		writer.Close()
		return nil, err
	}
	// A vector without its entry is left over from an interrupted write.
	for id := range c.vectors {
		if _, ok := c.items[id]; !ok {
			delete(c.vectors, id)
			c.dirty = true
		}
	}
	return c, nil
}

// loadItems reads the facts of every file back from the index.
func (c *Catalog) loadItems() error {
	reader, err := c.writer.Reader()
	if err != nil {
		return fmt.Errorf("catalog: reading index: %w", err)
	}
	defer reader.Close()
	matches, err := reader.Search(context.Background(), bluge.NewAllMatches(bluge.NewTermQuery("0").SetField(fieldPage)))
	if err != nil {
		return fmt.Errorf("catalog: listing entries: %w", err)
	}
	for match, err := matches.Next(); match != nil || err != nil; match, err = matches.Next() {
		if err != nil {
			return fmt.Errorf("catalog: listing entries: %w", err)
		}
		var decodeErr error
		visitErr := match.VisitStoredFields(func(field string, value []byte) bool {
			if field != fieldMeta {
				return true
			}
			var item Item
			if decodeErr = json.Unmarshal(value, &item); decodeErr == nil {
				c.items[item.ID] = &item
			}
			return false
		})
		if visitErr != nil || decodeErr != nil {
			return fmt.Errorf("catalog: reading an entry: %w", firstError(visitErr, decodeErr))
		}
	}
	return nil
}

func firstError(errs ...error) error {
	for _, err := range errs {
		if err != nil {
			return err
		}
	}
	return nil
}

func fileDocID(id string) string { return "f\x00" + id }

func pageDocID(id string, page int) string { return "p\x00" + id + "\x00" + strconv.Itoa(page) }

// Put adds entries, or replaces the ones with the same identifier: the pages,
// text and vectors of an entry that is put again are those of the new one.
func (c *Catalog) Put(items ...Item) error {
	for _, item := range items {
		if err := item.validate(); err != nil {
			return err
		}
	}
	c.mu.Lock()
	defer c.mu.Unlock()

	batch := bluge.NewBatch()
	for _, item := range items {
		if len(item.Pages) > 0 {
			item.PageCount = len(item.Pages)
		}
		if previous, ok := c.items[item.ID]; ok {
			for page := 1; page <= previous.PageCount; page++ {
				batch.Delete(bluge.Identifier(pageDocID(item.ID, page)))
			}
		}

		var vectors [][]float32
		for _, v := range item.Vectors {
			if n := normalized(v); n != nil {
				vectors = append(vectors, n)
			}
		}

		// The facts are stored without the bulky parts, which have their own place.
		meta := item
		meta.Pages, meta.Vectors = nil, nil
		encoded, err := json.Marshal(meta)
		if err != nil {
			return fmt.Errorf("catalog: encoding %q: %w", item.ID, err)
		}

		body := item.Text
		if len(item.Codes) > 0 {
			body += "\n" + strings.Join(item.Codes, "\n")
		}
		doc := bluge.NewDocument(fileDocID(item.ID)).
			AddField(bluge.NewKeywordField(fieldID, item.ID).StoreValue()).
			AddField(bluge.NewKeywordField(fieldPage, "0").StoreValue()).
			AddField(bluge.NewTextField(fieldName, searchableName(item)).WithAnalyzer(french).SearchTermPositions()).
			AddField(bluge.NewTextField(fieldBody, body).WithAnalyzer(french).SearchTermPositions().StoreValue()).
			AddField(bluge.NewStoredOnlyField(fieldMeta, encoded))
		batch.Update(doc.ID(), doc)

		for index, text := range item.Pages {
			page := index + 1
			pageDoc := bluge.NewDocument(pageDocID(item.ID, page)).
				AddField(bluge.NewKeywordField(fieldID, item.ID).StoreValue()).
				AddField(bluge.NewKeywordField(fieldPage, strconv.Itoa(page)).StoreValue()).
				AddField(bluge.NewTextField(fieldBody, text).WithAnalyzer(french).SearchTermPositions().StoreValue())
			batch.Update(pageDoc.ID(), pageDoc)
		}

		stored := meta
		c.items[item.ID] = &stored
		if len(vectors) > 0 {
			c.vectors[item.ID] = vectors
		} else {
			delete(c.vectors, item.ID)
		}
		c.dirty = true
	}
	if err := c.writer.Batch(batch); err != nil {
		return fmt.Errorf("catalog: writing entries: %w", err)
	}
	return nil
}

// searchableName is what a file can be found by: its name and the folders it
// is in, as separate words.
func searchableName(item Item) string {
	replacer := strings.NewReplacer("/", " ", "\\", " ", "_", " ", "-", " ", ".", " ")
	return replacer.Replace(item.Name + " " + item.Path)
}

// Delete removes entries. Unknown identifiers are ignored: a file deleted
// twice is deleted.
func (c *Catalog) Delete(ids ...string) error {
	c.mu.Lock()
	defer c.mu.Unlock()

	batch := bluge.NewBatch()
	for _, id := range ids {
		previous, ok := c.items[id]
		if !ok {
			continue
		}
		batch.Delete(bluge.Identifier(fileDocID(id)))
		for page := 1; page <= previous.PageCount; page++ {
			batch.Delete(bluge.Identifier(pageDocID(id, page)))
		}
		delete(c.items, id)
		delete(c.vectors, id)
		c.dirty = true
	}
	if err := c.writer.Batch(batch); err != nil {
		return fmt.Errorf("catalog: deleting entries: %w", err)
	}
	return nil
}

// Get returns the facts known about a file.
func (c *Catalog) Get(id string) (Item, bool) {
	c.mu.RLock()
	defer c.mu.RUnlock()
	item, ok := c.items[id]
	if !ok {
		return Item{}, false
	}
	return *item, true
}

// Len is the number of files in the catalog.
func (c *Catalog) Len() int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	return len(c.items)
}

// Counts returns the number of files of each kind, to tell the user what the
// library holds.
func (c *Catalog) Counts() map[query.Kind]int {
	c.mu.RLock()
	defer c.mu.RUnlock()
	counts := make(map[query.Kind]int)
	for _, item := range c.items {
		counts[item.Kind]++
	}
	return counts
}

// Flush makes everything written so far survive a restart.
func (c *Catalog) Flush() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.flush()
}

func (c *Catalog) flush() error {
	if c.dir == "" || !c.dirty {
		return nil
	}
	if err := saveVectors(c.dir, c.vectors); err != nil {
		return err
	}
	c.dirty = false
	return nil
}

// Close saves what is pending and releases the index.
func (c *Catalog) Close() error {
	c.mu.Lock()
	defer c.mu.Unlock()
	return firstError(c.flush(), c.writer.Close())
}

// All returns every entry, in no particular order.
func (c *Catalog) All() []Item {
	c.mu.RLock()
	defer c.mu.RUnlock()
	out := make([]Item, 0, len(c.items))
	for _, item := range c.items {
		out = append(out, *item)
	}
	return out
}
