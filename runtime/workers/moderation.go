package workers

import (
	"chat-lab/contract"
	"chat-lab/domain/event"
	"chat-lab/domain/specialist"
	"chat-lab/moderation"
	"context"
	"log/slog"
	"math/rand"
	"time"

	"github.com/abadojack/whatlanggo"
)

type ModerationWorker struct {
	moderator      moderation.Moderator
	manager        contract.SpecialistCoordinator
	moderationChan chan event.Event
	events         chan event.Event
	log            *slog.Logger
}

func NewModerationWorker(moderator moderation.Moderator,
	manager contract.SpecialistCoordinator,
	moderationChan,
	events chan event.Event, log *slog.Logger) *ModerationWorker {
	return &ModerationWorker{
		moderator:      moderator,
		manager:        manager,
		moderationChan: moderationChan, events: events, log: log,
	}
}

func (w ModerationWorker) Run(ctx context.Context) error {
	for {
		select {
		case <-ctx.Done():
			w.log.Debug("Stopping worker ")
			return ctx.Err()
		case e, ok := <-w.moderationChan:
			if !ok {
				w.log.Debug("Channel is closed")
				return nil
			}
			switch evt := e.Payload.(type) {
			case event.MessagePosted:
				select {
				case <-ctx.Done():
					w.log.Debug("Stopping worker")
					return ctx.Err()
				case w.events <- w.processAndSanitize(ctx, evt):
				}
			}
		}
	}
}

// processAndSanitize handles the transformation of a raw message into a sanitized event.
// It combines fast local operations (language detection, keyword filtering) with
// asynchronous gRPC calls to external specialists for advanced scoring (Topic 4).
// It aggregates specialist verdicts to determine the final toxicity score
// and business flags before routing the event to the domain pipeline.
func (w ModerationWorker) processAndSanitize(ctx context.Context, evt event.MessagePosted) event.Event {
	info := whatlanggo.Detect(evt.Content)
	lang := info.Lang.Iso6391()
	w.log.Debug("Detected language before moderation", "lang", lang)

	sanitized, foundWords := w.moderator.Censor(evt.Content)

	// Lead time measurement
	results := w.manager.Broadcast(ctx, evt.ID.String(), evt.Content)
	if len(results) == 0 {
		w.log.Warn("no specialists available for analysis, message unverified")
	}

	for specialistID, res := range results {
		if score, ok := (res.OneOf).(specialist.Score); ok {
			w.log.Warn("specialist alert",
				"id", specialistID,
				"score", score.Score,
			)
		}
	}

	return event.Event{
		Type:      event.DomainType,
		CreatedAt: time.Now().UTC(),
		Payload: event.SanitizedMessage{
			Room:          evt.Room,
			Author:        evt.Author,
			Content:       sanitized,
			CensoredWords: foundWords,
			At:            evt.At,
			ToxicityScore: decide(results),
		}}
}

// decide decides if the message should be dropped or forwarded
// Completely random right now
// Supposed to aggregate all scores from AI
func decide(_ map[specialist.Metric]specialist.Response) float64 {
	return 0.4 + rand.Float64()*(0.6-0.4)
}
