FROM golang:1.25-alpine

#  Install all necessary tools for profiling
RUN apk add --no-cache graphviz make gcc musl-dev

WORKDIR /app
COPY . .

RUN go mod download