//go:build linux

package runtime

import (
	"os/exec"
	"syscall"
)

// setPlatformSpecificAttrs configures process attributes specifically for Linux systems.
// It uses Pdeathsig to ensure that the child process (Python specialist) is
// automatically terminated by the kernel if the parent process (Go Master) exits.
func setPlatformSpecificAttrs(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{
		Pdeathsig: syscall.SIGKILL,
	}
}
