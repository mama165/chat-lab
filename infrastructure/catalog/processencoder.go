package catalog

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"os/exec"
	"sync"
)

// ProcessEncoder is a TextEncoder served by another program on this machine,
// typically the text side of the vision model, which lives in another
// language. The two talk over the standard input and output of the program,
// one JSON line each way: no network is involved.
//
//	→ {"text": "une rose rouge"}
//	← {"vector": [0.01, -0.02, ...]}     or     {"error": "..."}
//
// The program is started once and kept: loading a model takes seconds, a
// question must not pay for it every time.
type ProcessEncoder struct {
	mu     sync.Mutex
	cmd    *exec.Cmd
	stdin  io.WriteCloser
	stdout *bufio.Reader
	failed error
}

// StartProcessEncoder starts the program. It is ready when its first answer
// comes back, which the first call to EncodeText waits for.
func StartProcessEncoder(name string, args ...string) (*ProcessEncoder, error) {
	cmd := exec.Command(name, args...)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, err
	}
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, err
	}
	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("catalog: starting the encoder: %w", err)
	}
	return &ProcessEncoder{cmd: cmd, stdin: stdin, stdout: bufio.NewReaderSize(stdout, 1<<20)}, nil
}

// EncodeText implements TextEncoder. Calls are answered one at a time. If the
// context ends before the answer, the program is stopped: its next answer
// would belong to a question nobody waits for any more.
func (p *ProcessEncoder) EncodeText(ctx context.Context, text string) ([]float32, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failed != nil {
		return nil, p.failed
	}
	request, err := json.Marshal(map[string]string{"text": text})
	if err != nil {
		return nil, err
	}

	type answer struct {
		line []byte
		err  error
	}
	done := make(chan answer, 1)
	go func() {
		if _, err := p.stdin.Write(append(request, '\n')); err != nil {
			done <- answer{err: err}
			return
		}
		line, err := p.stdout.ReadBytes('\n')
		done <- answer{line: line, err: err}
	}()

	select {
	case <-ctx.Done():
		p.stop(fmt.Errorf("catalog: the encoder was stopped: %w", ctx.Err()))
		return nil, p.failed
	case got := <-done:
		if got.err != nil {
			p.stop(fmt.Errorf("catalog: the encoder stopped answering: %w", got.err))
			return nil, p.failed
		}
		var reply struct {
			Vector []float32 `json:"vector"`
			Error  string    `json:"error"`
		}
		if err := json.Unmarshal(got.line, &reply); err != nil {
			p.stop(fmt.Errorf("catalog: unreadable answer from the encoder: %w", err))
			return nil, p.failed
		}
		if reply.Error != "" {
			// The program is fine; this text could not be encoded.
			return nil, errors.New("catalog: encoder: " + reply.Error)
		}
		if len(reply.Vector) == 0 {
			return nil, errors.New("catalog: the encoder returned no vector")
		}
		return reply.Vector, nil
	}
}

// stop ends the program and makes every later call fail with the same reason.
func (p *ProcessEncoder) stop(reason error) {
	p.failed = reason
	_ = p.stdin.Close()
	if p.cmd.Process != nil {
		_ = p.cmd.Process.Kill()
	}
	_ = p.cmd.Wait()
}

// Close stops the program.
func (p *ProcessEncoder) Close() error {
	p.mu.Lock()
	defer p.mu.Unlock()
	if p.failed == nil {
		p.stop(errors.New("catalog: the encoder is closed"))
	}
	return nil
}
