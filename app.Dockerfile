# English comments in Golang code
FROM golang:1.24-alpine

# Install essential tools
RUN apk add --no-cache libc6-compat gcompat net-tools procps

WORKDIR /app

# Dependency cache
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Build binaries into /usr/local/bin so they aren't shadowed by the volume
RUN go build -o /usr/local/bin/master ./cmd/master
RUN go build -o /usr/local/bin/specialist ./cmd/specialist

RUN chmod +x /usr/local/bin/master /usr/local/bin/specialist

# Use the absolute path to the binary
CMD ["/usr/local/bin/master"]