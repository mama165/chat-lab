APP_NAME := chatlab
GO       := go
CONF     := cmd/chat.conf
CMD      := ./cmd

ifneq ("$(wildcard $(CONF))","")
	include $(CONF)
	export
endif

.PHONY: build
build:
	$(GO) build -o $(APP_NAME) $(CMD)

.PHONY: run
run:
	$(GO) run $(CMD)

.PHONY: debug
debug:
	LOG_LEVEL=DEBUG $(GO) run $(CMD)
