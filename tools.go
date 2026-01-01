//go:build tools
// +build tools

// Package tools declares tool dependencies for this module.
//
// These imports are not used at runtime. They exist solely to ensure that
// Go-based tools (invoked via `go generate`, e.g. mockgen) are tracked as
// explicit module dependencies.
//
// This makes tooling reproducible, keeps go.mod / go.sum in sync,
// and prevents "missing go.sum entry" errors when running `go generate`
// on a fresh checkout or in CI.
package chat_lab

import (
	_ "go.uber.org/mock/mockgen"
)
