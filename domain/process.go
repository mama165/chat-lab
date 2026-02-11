package domain

type Process struct {
	PID    PID
	Metric Metric
}

type PID int32
type PIDStatus string

const (
	RUNNING PIDStatus = "RUNNING"
	SLEEP   PIDStatus = "SLEEP"
	STOP    PIDStatus = "STOP"
	IDLE    PIDStatus = "IDLE"
	ZOMBIE  PIDStatus = "ZOMBIE"
	WAIT    PIDStatus = "WAIT"
	LOCK    PIDStatus = "LOCK"
	UNKNOWN PIDStatus = "UNKNOWN"
)

func ToPIDStatus(status string) PIDStatus {
	switch status {
	case "R", "RUNNING":
		return RUNNING
	case "S", "SLEEP", "SLEEPING":
		return SLEEP
	case "T", "STOP", "STOPPED":
		return STOP
	case "I", "IDLE":
		return IDLE
	case "Z", "ZOMBIE":
		return ZOMBIE
	case "W":
		return WAIT
	case "L":
		return LOCK
	default:
		return UNKNOWN
	}
}
