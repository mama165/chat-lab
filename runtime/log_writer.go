package runtime

import "log/slog"

// specialistLogWriter is a custom io.Writer that redirects a subprocess's
// standard output (stdout/stderr) to the main application's slog.Logger.
// It prefixes each log entry with the specialist's ID to provide clear
// traceability in a multi-specialist environment.
type specialistLogWriter struct {
	logger  *slog.Logger
	prefix  string
	isError bool
}

// Write implements the io.Writer interface. It captures the bytes from the
// subprocess, converts them to a string, and logs them using the appropriate
// severity level while maintaining the specialist's context.
func (w *specialistLogWriter) Write(p []byte) (n int, err error) {
	if len(p) == 0 {
		return 0, nil
	}

	// Clean the message (remove trailing newlines that subprocesses often add)
	msg := string(p)

	if w.isError {
		w.logger.Error(msg, "specialist", w.prefix)
	} else {
		w.logger.Info(msg, "specialist", w.prefix)
	}

	return len(p), nil
}
