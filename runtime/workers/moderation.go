package workers

import (
	"chat-lab/ai"
	"chat-lab/domain/event"
	"chat-lab/moderation"
	"context"
	"log/slog"
	"time"

	"github.com/abadojack/whatlanggo"
)

type ModerationWorker struct {
	moderator      moderation.Moderator
	analysis       *ai.Analysis
	moderationChan chan event.Event
	events         chan event.Event
	log            *slog.Logger
}

func NewModerationWorker(moderator moderation.Moderator,
	analysis *ai.Analysis, moderationChan,
	events chan event.Event, log *slog.Logger) *ModerationWorker {
	return &ModerationWorker{
		moderator:      moderator,
		analysis:       analysis,
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
				case w.events <- w.toSanitizedEvent(evt):
				}
			}
		}
	}
}

func (w ModerationWorker) toSanitizedEvent(evt event.MessagePosted) event.Event {
	info := whatlanggo.Detect(evt.Content)
	langCode := info.Lang.Iso6391()

	sanitized, foundWords := w.moderator.Censor(evt.Content)

	// Lead time measurement
	start := time.Now()
	score, isMalicious := w.analysis.CheckMessage(evt.Content)
	leadTime := time.Since(start)

	if isMalicious {
		w.log.Warn("AI Detection",
			"score", score,
			"lang", langCode,
			"author", evt.Author,
			"latency_us", leadTime.Microseconds())

		// Keep track of AI detection for future statistics
		foundWords = append(foundWords, "AI_MALICIOUS_DETECTION")
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
			ToxicityScore: score,
		}}
}
