FROM golang:1.25-alpine

RUN apk add --no-cache \
    bash curl git unzip \
    python3 py3-pip

# protoc
ARG PROTOC_VERSION=25.3
RUN curl -L https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip \
    -o /tmp/protoc.zip \
 && unzip /tmp/protoc.zip -d /usr/local \
 && rm /tmp/protoc.zip

# Go plugins
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@latest \
 && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest

# Buf v2
RUN curl -sSL https://github.com/bufbuild/buf/releases/download/v1.47.2/buf-Linux-x86_64 \
    -o /usr/local/bin/buf && chmod +x /usr/local/bin/buf

# Python gRPC
RUN pip install grpcio-tools --break-system-packages

ENV PATH="/go/bin:/usr/local/bin:${PATH}"
WORKDIR /src
