# 🚀 Chat-Lab | High-Performance Stream & Analysis Engine

Chat-Lab is a robust, modular messaging and data processing platform built in Go. Beyond simple chat, it is designed as a **resilient data pipeline** capable of ingesting, moderating, and indexing streams of information with advanced congestion control and self-healing capabilities.

---

## 🏗️ System Architecture

Chat-Lab follows a **Master-Worker-Sidecar** pattern to ensure total isolation between business logic and heavy processing (AI/Indexing).



### 1. The Orchestrator (The Brain)
* **Wait-and-Fail Backpressure:** Protects the system from bursts by rejecting messages (`ErrServerOverloaded`) if the internal buffer is full.
* **Fan-out Distribution:** Dispatches events to multiple Sinks (Storage, Search, Telemetry) in parallel without blocking the main flow.

### 2. The Supervisor (The Guardian)
* **Fault Isolation:** Every worker (Moderation, PoolUnit, Fan-out) is supervised.
* **Self-Healing:** In case of a `panic`, the Supervisor recovers, logs the failure, and restarts the worker automatically.

### 3. AI Specialists (The Muscles)
* **Cross-Language Sidecars:** Advanced analysis (Toxicity, Business Logic) is offloaded to specialized Python/C++ models.
* **gRPC IPC:** Communication via Protobuf ensures low-latency and strict contract definition between the Go Master and the AI Specialists.
* **Lead Time Monitoring:** Every analysis measures its own execution time to prevent pipeline congestion.

---

## 🛠️ Technical Stack

| Component | Technology | Purpose |
| :--- | :--- | :--- |
| **Core Engine** | Go 1.21+ | Concurrency, Goroutines, & High-speed routing |
| **Key-Value Store** | **BadgerDB** | Persistent storage for raw messages (LSM-tree) |
| **Search Engine** | **Bluge** | Full-text indexing and multi-criteria querying |
| **Communication** | **gRPC / Protobuf** | High-performance interface for AI sidecars |
| **Moderation** | **Aho-Corasick** | O(n) complexity keyword filtering |
| **Observability** | **Internal Telemetry** | Channel capacity monitoring & Restart health |

---

## ⚡ Performance & Resilience

* **Concurrency-First:** Uses a pool of unit workers to decouple command reception from processing.
* **Graceful Shutdown:** The Orchestrator ensures all internal channels are drained and repositories flushed before stopping.
* **Memory Efficiency:** Heavy operations like Aho-Corasick automaton building are done during the "Preparation Phase" to avoid runtime locks.
* **Modular Sinks:** Adding a new output (Kafka, S3, Webhook) is as simple as implementing the `EventSink` interface.

---

## 📊 Telemetry Channels

The system exposes 4 critical monitoring axes:
1.  **Channel Capacity:** Real-time length/capacity ratio of internal Go channels.
2.  **Censorship Hits:** Statistics on filtered words and moderated content.
3.  **Supervisor Health:** Tracking `RestartedAfterPanic` events.
4.  **Processing Latency:** Lead time measurement for gRPC Specialist calls.

---

## 🚀 Getting Started

### Load Testing
To stress the backpressure and throughput:
```bash
go test -v ./runtime/load_test.go