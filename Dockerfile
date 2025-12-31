FROM golang:1.25.1-alpine

ARG PROTOC_VERSION=25.3

RUN apk add --no-cache bash git curl unzip

# Installer protoc officiel
RUN curl -L https://github.com/protocolbuffers/protobuf/releases/download/v${PROTOC_VERSION}/protoc-${PROTOC_VERSION}-linux-x86_64.zip \
    -o /tmp/protoc.zip \
 && unzip /tmp/protoc.zip -d /usr/local \
 && rm /tmp/protoc.zip

# Installer plugins Go
RUN go install google.golang.org/protobuf/cmd/protoc-gen-go@v1.36.0 \
 && go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@v1.3.0

ENV PATH="/go/bin:/usr/local/bin:${PATH}"

WORKDIR /defs
ENTRYPOINT ["protoc"]
