APP_NAME := chatlab
GO       := go
CONF     := cmd/master/chat.conf
CMD_MASTER      := ./cmd/master
CMD_SPEC      := ./cmd/specialist
BIN_DIR    := ./bin

# Usie different  UID/GID because often readonly
D_UID := $(shell id -u)
D_GID := $(shell id -g)

# Configuration loading
ifneq ("$(wildcard $(CONF))","")
   include $(CONF)
   export
endif

# Use Badger path as defined in conf, or default /tmp/badger
DB_PATH ?= $(BADGER_FILEPATH)

.PHONY: build
build: build-master build-specialists
	$(GO) build -ldflags="-s -w" -o $(APP_NAME) $(CMD)

build-master:
	@echo "--- 🛠️ Building Master ---"
	$(GO) build -ldflags="-s -w" -o $(APP_NAME) $(CMD_MASTER)

build-specialists:
	@echo "--- 🛠️ Building Specialists ---"
	@mkdir -p $(BIN_DIR)
	@# On compile le même code source en deux binaires différents pour le Master
	$(GO) build -ldflags="-s -w" -o $(BIN_DIR)/toxicity $(CMD_SPEC)
	$(GO) build -ldflags="-s -w" -o $(BIN_DIR)/sentiment $(CMD_SPEC)
# --- Protobuf Generation ---

# Variables
PROTO_DIR = proto
# Updated to Go 1.24 to support latest protoc-gen-go-grpc requirements
BUILDER_IMAGE = golang:1.24-alpine

.PHONY: proto-gen
## proto-gen: Generate Go code using Go 1.24 environment
proto-gen:
	@echo "--- 🚀 Generating gRPC/Protobuf code with Go 1.24 ---"
	@docker run --rm -v $(PWD):/src -w /src $(BUILDER_IMAGE) sh -c "\
		apk add --no-cache protobuf-dev protoc && \
		go install google.golang.org/protobuf/cmd/protoc-gen-go@latest && \
		go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest && \
		protoc --proto_path=. \
			--experimental_allow_proto3_optional \
			--go_out=. --go_opt=paths=source_relative \
			--go-grpc_out=. --go-grpc_opt=paths=source_relative \
			$(shell find $(PROTO_DIR) -name "*.proto")"
	@echo "--- ✅ Generation completed ---"

.PHONY: proto-clean
## proto-clean: Remove all generated .pb.go files
proto-clean:
	@find . -name "*.pb.go" -delete
	@echo "--- 🧹 Deleted all generated files ---"

# --- Intelligence Artificielle ---

.PHONY: ai-gen
ai-gen:
	@echo "--- 🧠 Generating AI Model (Python Transpilation) ---"
	@# On passe D_UID et D_GID au shell de docker-compose
	D_UID=$(D_UID) D_GID=$(D_GID) docker-compose run --rm ai-gen

# --- Base de données ---

.PHONY: clean-db
clean-db:
	@echo "--- Checking for BadgerDB locks at $(DB_PATH) ---"
	@rm -f $(DB_PATH)/LOCK
	@if [ -f "$(DB_PATH)/LOCK" ]; then \
       fuser -k $(DB_PATH)/LOCK 2>/dev/null || true; \
    fi

# --- Exécution ---

.PHONY: run
run: clean-db build-specialists
	$(GO) run $(CMD_MASTER)

.PHONY: debug
debug: clean-db build-specialists
	LOG_LEVEL=DEBUG $(GO) run $(CMD_MASTER)

.PHONY: test
test:
	$(GO) test -v ./...

.PHONY: all
all: proto-gen ai-gen test build