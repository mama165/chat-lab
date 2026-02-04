//go:build windows

package runtime

import "os/exec"

// setPlatformSpecificAttrs configures process attributes for Windows systems.
// Note: Pdeathsig is not supported on Windows. Process lifecycle is managed
// via the context's termination signal (TerminateProcess) provided by exec.CommandContext.
func setPlatformSpecificAttrs(cmd *exec.Cmd) {
	if cmd.SysProcAttr == nil {
	}
}
