APP_NAME := chatlab
GO       := go
CONF     := cmd/chat.conf
CMD      := ./cmd

# On utilise des noms différents de UID/GID car ils sont souvent readonly
D_UID := $(shell id -u)
D_GID := $(shell id -g)

# Chargement de la configuration
ifneq ("$(wildcard $(CONF))","")
   include $(CONF)
   export
endif

# Utilisation du chemin Badger défini dans la conf, ou /tmp/badger par défaut
DB_PATH ?= $(BADGER_FILEPATH)

.PHONY: build
gbuild:
	$(GO) build -ldflags="-s -w" -o $(APP_NAME) $(CMD)

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
run: clean-db
	$(GO) run $(CMD)

.PHONY: debug
debug: clean-db
	LOG_LEVEL=DEBUG $(GO) run $(CMD)

.PHONY: test
test:
	$(GO) test -v ./...

.PHONY: all
all: ai-gen test build