package ai

import (
	"chat-lab/ai"
	"fmt"
	"strings"
	"testing"
)

// TestCase represents a single validation entry
type TestCase struct {
	Text        string
	IsMalicious bool
	Category    string
}

func TestMassiveValidation(t *testing.T) {
	analyzer := ai.NewAnalysis()
	cases := generateTestDataset()

	var passed int
	categories := make(map[string]struct {
		total  int
		passed int
	})

	fmt.Printf("\n--- 🧪 Chat-Lab AI Stress Test (%d samples) ---\n", len(cases))

	for _, tc := range cases {
		score, detected := analyzer.CheckMessage(tc.Text)

		success := detected == tc.IsMalicious

		stats := categories[tc.Category]
		stats.total++
		if success {
			stats.passed++
			passed++
		} else {
			t.Errorf("[%s] FAIL: '%s' | Score: %.4f | Expected: %v", tc.Category, tc.Text, score, tc.IsMalicious)
		}
		categories[tc.Category] = stats
	}

	fmt.Printf("\n--- 📊 Final Results ---\n")
	fmt.Printf("Global Accuracy: %.2f%% (%d/%d)\n", float64(passed)/float64(len(cases))*100, passed, len(cases))

	for name, s := range categories {
		fmt.Printf("Category %-15s: %d/%d (%.2f%%)\n", name, s.passed, s.total, float64(s.passed)/float64(s.total)*100)
	}
}

func generateTestDataset() []TestCase {
	var cases []TestCase

	// 1. HEALTHY - TECHNICAL (50 phrases)
	// Focus on programming, Kafka, Go, and system terms that could look like threats
	techKeywords := []string{"kill", "terminate", "panic", "abort", "deadlock", "execute", "injection"}
	for i := 0; i < 50; i++ {
		kw := techKeywords[i%len(techKeywords)]
		cases = append(cases, TestCase{
			Text:        fmt.Sprintf("I need to %s the worker process to avoid a memory leak in the Go service.", kw),
			IsMalicious: false,
			Category:    "Tech_Safe",
		})
	}

	// 2. TOXIC - DIRECT (50 phrases)
	// Using the priority words we trained in Python
	toxicPatterns := []string{"idiot", "loser", "naze", "pourriture", "crétin", "shutup"}
	for i := 0; i < 50; i++ {
		p := toxicPatterns[i%len(toxicPatterns)]
		cases = append(cases, TestCase{
			Text:        fmt.Sprintf("You are such a %s, go away and never come back.", p),
			IsMalicious: true,
			Category:    "Toxic_Direct",
		})
	}

	// 3. HEALTHY - SOCIAL (50 phrases)
	// Normal chat interactions
	greetings := []string{"Hello", "Good morning", "How are you", "Nice to meet you", "Thanks for the help"}
	for i := 0; i < 50; i++ {
		g := greetings[i%len(greetings)]
		cases = append(cases, TestCase{
			Text:        fmt.Sprintf("%s! I really appreciate the work you did on the Chat-Lab gRPC layer.", g),
			IsMalicious: false,
			Category:    "Social_Safe",
		})
	}

	// 4. TOXIC - LEET SPEAK & OBFUSCATION (30 phrases)
	// Testing if our vectorizer/tokenizer is robust
	leet := []string{"1diot", "n4ze", "l0ser", "v4 m0ur1r", "f*ck", "sh!t"}
	for i := 0; i < 30; i++ {
		l := leet[i%len(leet)]
		cases = append(cases, TestCase{
			Text:        fmt.Sprintf("Hey you %s, I hope your server crashes.", l),
			IsMalicious: true,
			Category:    "Toxic_Leet",
		})
	}

	// 5. EDGE CASES - LONG MESSAGES (20 phrases)
	for i := 0; i < 20; i++ {
		cases = append(cases, TestCase{
			Text:        strings.Repeat("This is a very long but perfectly safe message about database indexing. ", 10),
			IsMalicious: false,
			Category:    "Edge_Long",
		})
	}

	return cases
}
