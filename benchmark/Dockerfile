FROM golang:1.25-alpine

# Install tools for profiling and CGO (needed for some DB drivers)
RUN apk add --no-cache graphviz make gcc musl-dev

WORKDIR /app

# 1. Cache dependencies first
COPY go.mod go.sum ./
RUN go mod download

# 2. Copy the source code
COPY . .

# 3. Ready to run benchmarks!
# No need for another go mod download here