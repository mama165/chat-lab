APP_NAME := chatlab
GO       := go
CONF     := cmd/chat.conf
CMD      := ./cmd

# Chargement de la configuration
ifneq ("$(wildcard $(CONF))","")
   include $(CONF)
   export
endif

# Utilisation du chemin Badger défini dans la conf, ou /tmp/badger par défaut
DB_PATH ?= $(BADGER_FILEPATH)

.PHONY: build
build:
	$(GO) build -o $(APP_NAME) $(CMD)

# Cible de nettoyage pour éviter les blocages de lock Badger
.PHONY: clean-db
clean-db:
	@echo "--- Checking for BadgerDB locks at $(DB_PATH) ---"
	@rm -f $(DB_PATH)/LOCK
	@# fuser -k permet de tuer les processus zombies qui retiendraient le fichier
	@if [ -f "$(DB_PATH)/LOCK" ]; then \
		fuser -k $(DB_PATH)/LOCK 2>/dev/null || true; \
	fi

.PHONY: run
run: clean-db
	$(GO) run $(CMD)

.PHONY: debug
debug: clean-db
	LOG_LEVEL=DEBUG $(GO) run $(CMD)

.PHONY: test
test:
	$(GO) test -v ./...