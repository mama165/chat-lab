# Chat-Lab

Chat-Lab scans a folder and its subfolders, extracts the content of the files it finds, and indexes everything so that it can be searched later.

It is written in Go. The heavy extraction work (reading PDFs, transcribing audio, describing images) is delegated to small Python programs, the *specialists*, which run next to the main program.

> The name comes from the project's first life as a chat server. That version, with its rooms, moderation and authentication, is kept on the `archive/chat` branch.

## What it does

1. **Scan.** A pool of workers walks the folder tree. Symbolic links are skipped, and the type of each file is detected from its first bytes, not from its extension.
2. **Store.** The metadata of every supported file (path, size, type) is stored and indexed straight away, so a file can be found by its name before its analysis is finished.
3. **Analyze.** Each file is sent to the specialist that handles its type.
4. **Enrich.** What the specialist returns (title, author, text of the pages, transcription) is merged into the stored record, which makes the file searchable by its content.

Supported types today: PDF, audio (MP3, WAV, AIFF) and images (PNG, JPEG, GIF). What each specialist extracts:

| Type | Extracted | Stored as searchable content |
| :--- | :--- | :--- |
| PDF | title, author, text of the pages | the text |
| Audio | transcription, language, duration | the transcription |
| Image | width, height, format, color mode | a description such as `png image 800x600 RGB` |

## Architecture

A single Go program runs the whole pipeline. Each stage is a pool of supervised workers, and the stages are linked by bounded channels:

```
scanner -> ingest -> fan-out -> analysis sink (stores the file metadata)
                                      |
                                  job queue
                                      v
                  analysis workers -> specialists -> storage
```

- **Backpressure.** The channels are bounded and the flow never loops back. When the specialists are slow, the queues fill up and the scanner slows down on its own.
- **Supervision.** Every worker runs under a supervisor. If one panics, it is restarted without stopping the others.
- **Specialists.** Each specialist is a separate Python process, started by the Go program and reached over gRPC. Its CPU and memory use are monitored.
- **Telemetry.** Queue levels, worker restarts and specialist health are collected and exposed over HTTP.

More detail in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md), [docs/SEQUENCE.md](docs/SEQUENCE.md) and [docs/PERFORMANCE.md](docs/PERFORMANCE.md).

## Asking in plain French

A free sentence is turned into a search plan by the `domain/query` package, without any model. It can be tried without an index:

```bash
go run ./cmd/query "Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle."
```

```
Recherche : PDF · depuis le 26 avril 2026 · texte : notice, lave-vaisselle
```

Without a sentence, the command keeps the thread of a conversation (`pas celle de l'an dernier`, `et en vidéo ?`). See [docs/QUERY.md](docs/QUERY.md).

A plan is run on a catalog of the library, which returns the files, the page and an excerpt. With `-library`, the command searches a list of entries; a test library is provided. See [docs/CATALOG.md](docs/CATALOG.md).

## Technical stack

| Component | Technology | Purpose |
| :--- | :--- | :--- |
| Core engine | Go 1.27 | Scan, pipeline, supervision |
| Storage | BadgerDB | Embedded key-value store for the records |
| Search | Bluge | Embedded full-text index |
| Specialists | Python, gRPC / Protobuf | PDF (PyMuPDF), audio (faster-whisper), images (Pillow) |
| Type detection | mimetype | File type from the first bytes |

## Getting started

Requirements: Go 1.27 or later, and Python 3 for the specialists.

**1. Install the specialists' dependencies** (creates a `venv` folder):

```bash
./scripts/setup_env.sh
```

**2. Create the configuration.** The program reads a `.env` file in the folder it is started from:

```bash
cp internal/local.conf .env
```

The settings you are most likely to change:

| Setting | Meaning |
| :--- | :--- |
| `SCAN_ROOT_DIR` | Folder scanned at startup |
| `ANALYSIS_WORKER_NB` | Number of files sent to the specialists at the same time |
| `MAX_FILE_SIZE_MB` | Files larger than this are not analyzed |
| `ENABLE_SPECIALISTS` | Set to `false` to scan and index names only, without Python |
| `BADGER_FILEPATH`, `BLUGE_FILEPATH` | Where the database and the index are stored |
| `MONITORING_HOST`, `MONITORING_PORT` | Address and port of the HTTP server: search API and monitoring pages (`127.0.0.1` and `8092` by default) |

**3. Run it:**

```bash
go run ./cmd/master
```

To scan another folder without editing the configuration:

```bash
go run ./cmd/master -dir /path/to/folder
```

The program scans the folder, then keeps running while the specialists work through the files. Stop it with Ctrl+C.

## Search

While the program runs, the indexed files can be searched over HTTP:

```bash
curl "http://localhost:8092/api/search?q=invoice+roof"
```

| Parameter | Meaning |
| :--- | :--- |
| `q` | Text to look for. A file matches when it contains every word, in its name, its path or its extracted content. Required. |
| `type` | `pdf`, `audio` or `image`, to keep one kind of file. |
| `page` | Page number, starting at 1. |
| `size` | Results per page: 20 by default, 100 at most. |

The answer gives the total number of matching files and, for each result of the page, its path, name, type, size, title when a specialist found one, and an excerpt of the content around the first word found. The search covers every scanned drive, best matches first.

The HTTP server only accepts connections from the machine it runs on. To reach it from another device, set `MONITORING_HOST=0.0.0.0`; there is no authentication, so only do this on a network you trust.

## Monitoring

While the program runs:

- `http://localhost:8092/monitoring.html` shows the health of the program and of each specialist.
- `http://localhost:8092/api/monitoring` returns the same data as JSON.
- `http://localhost:8092/api/stats` returns the scan progress and throughput as JSON.

## Development

```bash
go build ./...
```

```bash
go test -race ./...
```

Add `-short` to skip the slow storage test that indexes several thousand documents.

The tests in `e2e/` run the real Python specialists on files from `test_data/`: a PDF, a spoken recording and a picture, alone and then together through the whole pipeline. They need the environment created by `./scripts/setup_env.sh`, and the first audio run downloads the Whisper model (about 140 MB). Without that environment they are skipped. To run only them:

```bash
go test -v ./e2e/
```

The CI runs seven independent jobs on every pull request: build, code quality (vet, gofmt, golangci-lint), security (govulncheck), generated code (mocks), tests with the race detector, benchmarks, and the specialists on real files.

After changing an interface that has a mock, regenerate the mocks:

```bash
go generate ./...
```

## Known limitations

- **No search page yet.** The search is an HTTP API; there is no screen to type a query in.
- **No incremental scan.** Every run scans the whole folder again and creates new records, so a file scanned twice into the same database appears twice in the search results.
- **Files are loaded in memory** before being sent to a specialist, up to `MAX_FILE_SIZE_MB` each.
- **`.aiff` files are not picked up by the scanner**, although the audio specialist declares the type.
- **The Whisper model is downloaded automatically** the first time the audio specialist starts. There is no setting yet to use a model already present on disk.
