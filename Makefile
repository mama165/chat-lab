APP_NAME    := chatlab
GO          := go
CONF        := cmd/master/chat.conf
CMD_MASTER  := ./cmd/master
CMD_SPEC    := ./cmd/specialist
BIN_DIR     := ./bin

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

## proto-gen: Generate Go code from .proto files using a containerized environment
proto-gen:
	@echo "--- 🚀 Generating gRPC/Protobuf code ---"
	@docker run --rm -v $(PWD):/src -w /src golang:1.24-alpine sh -c "\
	   apk add --no-cache protobuf-dev protoc && \
	   go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
	   go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest && \
	   protoc --proto_path=. --experimental_allow_proto3_optional --go_out=. --go_opt=paths=source_relative --go-grpc_out=. --go-grpc_opt=paths=source_relative $$(find proto -name "*.proto")"
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
	docker-compose down -v