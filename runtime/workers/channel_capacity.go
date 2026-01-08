package workers

import (
	"chat-lab/domain/event"
	"context"
	"log/slog"
	"reflect"
	"time"
)

type NamedChannel struct {
	Name    string
	Channel any
}

// ChannelCapacityWorker periodically reports the current channel capacity and length.
// Reading len(channel) and cap(channel) is non-blocking, so this won't interfere
// with other goroutines. It's okay if an domainEvent is dropped occasionally because
// metrics are sampled periodically.
type ChannelCapacityWorker struct {
	log            *slog.Logger
	channels       []NamedChannel
	telemetryChan  chan event.Event
	metricInterval time.Duration
}

func NewChannelCapacityWorker(log *slog.Logger,
	channels []NamedChannel, telemetryChan chan event.Event,
	metricInterval time.Duration) *ChannelCapacityWorker {
	return &ChannelCapacityWorker{
		log: log, channels: channels,
		telemetryChan:  telemetryChan,
		metricInterval: metricInterval,
	}
}

func (w ChannelCapacityWorker) Run(ctx context.Context) error {
	ticker := time.NewTicker(w.metricInterval)
	defer ticker.Stop()
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Context done, stopping domainEvent send")
			return nil
		case <-ticker.C:
			for _, nc := range w.channels {
				v := reflect.ValueOf(nc.Channel)
				// Verify if this is a channel
				if v.Kind() != reflect.Chan {
					w.log.Error("Provided object is not a channel", "name", nc.Name)
					continue
				}
				capacity := v.Cap()
				length := v.Len()
				select {
				case <-ctx.Done():
					w.log.Debug("Context done, stopping domainEvent send")
					return nil
				case w.telemetryChan <- toCapacityEvent(nc.Name, capacity, length):
				default:
					w.log.Debug("Observability telemetry event lost")
				}
			}
		}
	}
}

func toCapacityEvent(name string, capacity, length int) event.Event {
	return event.Event{
		Type:      event.ChannelCapacityType,
		CreatedAt: time.Now().UTC(),
		Payload: event.ChannelCapacity{
			ChannelName: name,
			Capacity:    capacity,
			Length:      length,
		},
	}
}
