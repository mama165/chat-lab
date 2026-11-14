package query

import (
	"context"
	"time"
)

// NoiseJudge tells conversation from description.
//
// The rules read everything exact in a sentence. What they cannot know is
// whether a leftover word describes what is searched or is just talk:
// "absolument", "je crois que", "ressortir". A judge is given the sentence and
// those leftover words, and returns the ones to ignore.
//
// On a phone, the judge is a small language model. Its power is deliberately
// narrow: it can only remove words from the description. It cannot add a
// filter, move a date or choose a kind of file, so whatever it answers, it
// cannot invent a criterion.
type NoiseJudge interface {
	Noise(ctx context.Context, sentence string, candidates []string) ([]string, error)
}

// ParseWith translates a sentence with the help of a judge. The plan of the
// rules is the fallback in every case: no judge, an error, a timeout, or a
// judge that would leave nothing to search.
//
// The judge is not asked anything when fewer than two words are left: a lone
// word is what the user is after, and measurements showed small models tend to
// drop it.
func (p *Parser) ParseWith(ctx context.Context, judge NoiseJudge, sentence string, now time.Time) Result {
	base := p.analyze(sentence, now, nil)
	result := Result{Plan: base.plan, Then: base.then}
	if judge == nil || len(base.candidates) < 2 {
		return result
	}
	noise, err := judge.Noise(ctx, sentence, base.candidates)
	if err != nil || len(noise) == 0 {
		return result
	}
	// Only words that were offered may be dropped.
	offered := make(map[string]struct{}, len(base.candidates))
	for _, word := range base.candidates {
		offered[normalize(word)] = struct{}{}
	}
	var accepted []string
	for _, word := range noise {
		if _, ok := offered[normalize(word)]; ok {
			accepted = append(accepted, word)
		}
	}
	return p.ParseDropping(sentence, now, accepted)
}
