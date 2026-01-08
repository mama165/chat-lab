package event

// Handler Each kind of event has his own handler
// Based on the Chain of responsibility pattern
type Handler interface {
	Handle(event Event)
}
