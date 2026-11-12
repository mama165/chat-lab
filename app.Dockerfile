# English comments in Golang code
FROM golang:1.27-alpine

# Install essential tools
RUN apk add --no-cache libc6-compat gcompat net-tools procps

WORKDIR /app

# Dependency cache
COPY go.mod go.sum ./
RUN go mod download

COPY . .

# Build the binary into /usr/local/bin so it isn't shadowed by the volume
RUN go build -o /usr/local/bin/master ./cmd/master

# Use the absolute path to the binary
CMD ["/usr/local/bin/master"]