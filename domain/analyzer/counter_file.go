package analyzer

import "sync/atomic"

type CounterFileScanner struct {
	FilesScanned   uint64
	DirsScanned    uint64
	BytesProcessed uint64
	ErrorCount     uint64
	SkippedItems   uint64
}

func NewCounterFileScanner() *CounterFileScanner {
	return &CounterFileScanner{
		FilesScanned:   0,
		DirsScanned:    0,
		BytesProcessed: 0,
		ErrorCount:     0,
		SkippedItems:   0,
	}
}

func (c *CounterFileScanner) IncrFilesScanned() {
	atomic.AddUint64(&c.FilesScanned, 1)
}

func (c *CounterFileScanner) IncrDirsScanned() {
	atomic.AddUint64(&c.DirsScanned, 1)
}

func (c *CounterFileScanner) IncrSizeFound(size uint64) {
	atomic.AddUint64(&c.BytesProcessed, size)
}

func (c *CounterFileScanner) IncrErrorCount() {
	atomic.AddUint64(&c.ErrorCount, 1)
}

func (c *CounterFileScanner) IncrSkippedItems() {
	atomic.AddUint64(&c.SkippedItems, 1)
}
