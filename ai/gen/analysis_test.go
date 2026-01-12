package ai

import (
	"chat-lab/ai"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestIsMalicious_Robustness(t *testing.T) {
	req := require.New(t)

	tests := []struct {
		name       string
		input      string
		wantMalice bool
	}{
		// --- ANGLAIS (Dataset Davidson) ---
		{"EN_Toxic_Direct", "You are a complete idiot and I hate you", true},
		{"EN_Toxic_Slang", "Shut up you loser", true},
		{"EN_Healthy_Tech", "I am currently debugging the Kafka consumer", false},
		{"EN_Healthy_Social", "How are you doing today my friend?", false},

		// --- FRANÇAIS (Injecté via French Data) ---
		{"FR_Toxic_Direct", "Espèce de gros naze", true},
		{"FR_Toxic_Severe", "Va mourir sale pourriture", true},
		{"FR_Healthy_Neutral", "Je ne suis pas d'accord avec ton analyse", false},
		{"FR_Healthy_Greeting", "Bonjour tout le monde, j'espère que vous allez bien", false},

		// --- CAS LIMITES (Edge Cases) ---
		{"Edge_Kill_Process", "I need to kill the process 1234", false},
		{"Edge_Empty", "", false},
		{"Edge_Long_Healthy", strings.Repeat("This is a very long and perfectly normal message about Golang. ", 5), false},
		{"Edge_Punctuation", "YOU!!!!! ARE????? NAZE!!!!!", true},
	}

	for _, tt := range tests {
		t.Run(tt.name, func(t *testing.T) {
			isMalicious, score := ai.IsMalicious(tt.input)

			// Log du score pour le debugging fin
			t.Logf("Test: %-20s | Score: %.4f | Malicious: %v", tt.name, score, isMalicious)

			// require.Equal offre un message d'erreur clair en cas d'échec
			req.Equal(tt.wantMalice, isMalicious, "Mismatch for input: %s (Score: %.4f)", tt.input, score)
		})
	}
}
