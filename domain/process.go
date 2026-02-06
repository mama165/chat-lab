package domain

type Process struct {
	PID    PID
	Metric Metric
}

type PID int32
type PidStatus string

const (
	RUNNING PidStatus = "RUNNING"
	SLEEP   PidStatus = "SLEEP"
	STOP    PidStatus = "STOP"
	IDLE    PidStatus = "IDLE"
	ZOMBIE  PidStatus = "ZOMBIE"
	WAIT    PidStatus = "WAIT"
	LOCK    PidStatus = "LOCK"
	UNKNOWN PidStatus = "UNKNOWN"
)

func ToStatus(status string) PidStatus {
	switch status {
	case "R":
		return RUNNING
	case "S":
		return SLEEP
	case "T":
		return STOP
	case "I":
		return IDLE
	case "Z":
		return ZOMBIE
	case "W":
		return WAIT
	case "L":
		return LOCK
	default:
		return UNKNOWN
	}
}
