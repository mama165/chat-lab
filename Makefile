APP_NAME    := chatlab
GO          := go
CONF        := cmd/master/chat.conf
CMD_MASTER  := ./cmd/master
CMD_SPEC    := ./cmd/specialist
BIN_DIR     := ./bin
BENCHMARK_IMAGE_NAME=benchmark
BENCHMARK_DIR=benchmark
BENCHMARK_FILE_RESULT=benchmark.pdf
DOC_COUNT_PROFILING=10000

BINARY=bin/badger_inspect
SOURCE=tools/badger_inspect.go
DB_PATH=/tmp/database/debug

PY_SPEC_DIR=./proto

PROTO_ROOT := proto

# Docker user identifiers to avoid permission issues on host
D_UID := $(shell id -u)
D_GID := $(shell id -g)

# Configuration loading
ifneq ("$(wildcard $(CONF))","")
   include $(CONF)
   export
endif

# Database path for Badger
DB_PATH ?= $(BADGER_FILEPATH)

# --- Phony Targets Declaration ---
.PHONY: build build-master build-specialists proto-gen ai-gen clean-db lab dev clean fmt

# --- Code Formatting ---

## fmt: Format all Go source files in the project
fmt:
	@echo "--- 🎨 Formatting Go code ---"
	@$(GO) fmt ./...

# --- Local Build ---

## build: Build all local binaries (Master and Specialists)
build: build-master build-specialists

build-master:
	@echo "--- 🛠️ Building Master binary ---"
	$(GO) build -ldflags="-s -w" -o $(APP_NAME) $(CMD_MASTER)

build-specialists:
	@echo "--- 🛠️ Building Specialist binaries ---"
	@mkdir -p $(BIN_DIR)
	$(GO) build -ldflags="-s -w" -o $(BIN_DIR)/toxicity $(CMD_SPEC)
	$(GO) build -ldflags="-s -w" -o $(BIN_DIR)/sentiment $(CMD_SPEC)



# --- Code Generation ---

## proto-gen: Generate Go and Python code from .proto files recursively
proto-gen:
	@echo "--- 🧹 Cleaning old generated code ---"
	@find proto -name "*.pb.go" -type f -delete
	@find proto -name "*_pb2.py" -type f -delete
	@find proto -name "*_pb2_grpc.py" -type f -delete
	@echo "--- 🚀 Generating gRPC/Protobuf code for all contracts ---"
	@docker run --rm -v "$(PWD):/src" -w /src golang:1.24-alpine sh -c "\
       apk add --no-cache protobuf-dev protoc python3 py3-pip && \
       pip install grpcio-tools --break-system-packages && \
       go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
       go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest && \
       export PATH=\"\$$PATH:\$$(go env GOPATH)/bin\" && \
       protoc -I. --go_out=. --go_opt=paths=source_relative --go-grpc_out=. --go-grpc_opt=paths=source_relative \$$(find proto -name '*.proto') && \
       python3 -m grpc_tools.protoc -I. --python_out=. --grpc_python_out=. \$$(find proto -name '*.proto') && \
       find proto -type d -exec touch {}/__init__.py \;"
	@$(MAKE) fmt

## ai-gen: Run the AI model generation (Python transpilation) and force formatting
ai-gen:
	@echo "--- 🧠 Generating AI Model (Python Transpilation) ---"
	D_UID=$(D_UID) D_GID=$(D_GID) docker-compose run --rm ai-gen
	@echo "--- ⏳ Waiting for filesystem sync ---"
	@sleep 1
	@$(MAKE) fmt

# --- Maintenance ---

## clean-db: Remove BadgerDB LOCK files to prevent startup failures
clean-db:
	@echo "--- 🧹 Cleaning Badger locks at $(DB_PATH) ---"
	@rm -f $(DB_PATH)/LOCK

# --- Execution Entry Points ---

## lab: Run the full orchestrated ecosystem in Docker (with cleanup and formatting)
lab: clean-db fmt
		@echo "--- 🔬 Launching Chat-Lab Ecosystem in Docker (Lab Mode) ---"
		D_UID=$(D_UID) D_GID=$(D_GID) docker-compose up --build --abort-on-container-exit
		@echo "--- 🧼 Post-execution cleanup & formatting ---"
		@$(MAKE) fmt

## dev: Run Master locally for fast iteration
dev: clean-db fmt build-specialists
	@echo "--- 🛠️ Running Master in local development mode ---"
	$(GO) run $(CMD_MASTER)

## clean: Remove binaries and stop containers
clean:
	@echo "--- 🧹 Cleaning binaries and Docker containers ---"
	rm -f $(APP_NAME)
	rm -rf $(BIN_DIR)
	rm -rf $(BIN_DIR)
	docker-compose down -v


.PHONY: bench-profile

bench-profile:
	@echo "🚀 Docker benchmark build..."
	docker build -t $(BENCHMARK_IMAGE_NAME) -f ./benchmark/Dockerfile .

	@echo "🏃 Benchmark execution..."
	# Ensure the output directory exists
	mkdir -p ./benchmark
	# Run tests and generate cpu.out
	# The "-" prefix allows the Makefile to continue even if the test fails
	-docker run --rm -v $(PWD):/app $(BENCHMARK_IMAGE_NAME) \
	   go test -v ./repositories \
	   -run=TestAnalysisRepository_Search_100kDocuments \
	   -cpuprofile=/app/$(BENCHMARK_DIR)/benchmark.out \
	   -timeout=5m \
	   -args -count=$(DOC_COUNT_PROFILING)

	@echo "📊 PDF report generation..."
	# We mount the root directory to /app so pprof can see both the source profile and the output destination
	docker run --rm -v $(PWD):/app $(BENCHMARK_IMAGE_NAME) \
	   go tool pprof -pdf -output=/app/$(BENCHMARK_DIR)/$(BENCHMARK_FILE_RESULT) /app/$(BENCHMARK_DIR)/benchmark.out

	@echo "✅ Report  successfully generated : ./$(BENCHMARK_DIR)/$(BENCHMARK_FILE_RESULT)"

# Default prefix if none provided
prefix ?= analysis:

# Target to run the inspector
.PHONY: select
select: $(BINARY)
	@./$(BINARY) -db $(DB_PATH) -prefix "$(prefix)"

# Build target: only runs if $(SOURCE) is newer than $(BINARY)
$(BINARY): $(SOURCE)
	@echo "🔨 Build: $(SOURCE) -> $(BINARY)"
	@mkdir -p bin
	@go build -o $(BINARY) $(SOURCE)