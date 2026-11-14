package query

import (
	"testing"

	"github.com/stretchr/testify/require"
)

// say runs sentences one after the other and returns the last turn.
func say(t *testing.T, session *Session, sentences ...string) Turn {
	t.Helper()
	var turn Turn
	for _, sentence := range sentences {
		turn = session.Interpret(sentence, testNow)
		if turn.Action == ActionNew || turn.Action == ActionRefine {
			require.Empty(t, Validate(turn.Plan, testNow, session.Sentences()...), "after %q", sentence)
		}
	}
	return turn
}

func TestSession_Dialogues(t *testing.T) {
	cases := []struct {
		name      string
		sentences []string
		action    Action
		summary   string
	}{
		{"exclude a period",
			[]string{"trouve-moi une photo de cuisine", "pas celle de l'an dernier"},
			ActionRefine, "photos · sauf 2025 · image : cuisine"},
		{"restrict to a kind",
			[]string{"trouve le contrat de location", "montre seulement les PDF"},
			ActionRefine, "PDF · texte : contrat, location"},
		{"same subject, other kind",
			[]string{"trouve-moi une photo de cuisine", "et en vidéo ?"},
			ActionRefine, "vidéos · image : cuisine"},
		{"same subject, from pictures to documents",
			[]string{"trouve-moi une photo de cuisine", "la même chose en PDF"},
			ActionRefine, "PDF · texte : cuisine"},
		{"from documents to pictures",
			[]string{"le PDF où il est question d'un notaire", "plutôt en photo"},
			ActionRefine, "photos · image : un notaire"},
		{"add a date",
			[]string{"les vidéos d'un paysage de montagne", "de l'été dernier"},
			ActionRefine, "vidéos · du 1er juin au 30 septembre 2026 · image : un paysage de montagne"},
		{"change the date",
			[]string{"les PDF de l'an dernier sur les impôts", "en 2024"},
			ActionRefine, "PDF · 2024 · texte : impôts"},
		{"newest first",
			[]string{"le PDF où il est question d'un notaire", "plus récent"},
			ActionRefine, "PDF · texte : notaire · plus récents d'abord"},
		{"older",
			[]string{"le PDF où il est question d'un notaire", "avant ça"},
			ActionRefine, "PDF · texte : notaire · plus anciens d'abord"},
		{"add to the subject",
			[]string{"trouve-moi une photo de cuisine", "avec un chien"},
			ActionRefine, "photos · image : cuisine, un chien"},
		{"replace the subject",
			[]string{"trouve-moi une photo de cuisine", "plutôt un salon"},
			ActionRefine, "photos · image : un salon"},
		{"remove from the subject",
			[]string{"trouve-moi une photo de plage", "sans personne"},
			ActionRefine, "photos · image : plage · sans personne"},
		{"remove a kind",
			[]string{"photos et vidéos du mariage", "sans les vidéos"},
			ActionRefine, "photos · image : mariage"},
		{"removing the only kind keeps the others",
			[]string{"les vidéos du mariage", "pas les vidéos"},
			ActionRefine, "photos · PDF · audio · image : mariage"},
		{"several adjustments in a row",
			[]string{"trouve-moi une photo de cuisine", "pas celle de l'an dernier", "et en vidéo ?", "plus récent"},
			ActionRefine, "vidéos · sauf 2025 · image : cuisine · plus récents d'abord"},
		{"an order starts over",
			[]string{"trouve-moi une photo de cuisine", "trouve-moi le PDF du notaire"},
			ActionNew, "PDF · texte : notaire"},
		{"a new subject starts over",
			[]string{"trouve-moi une photo de cuisine", "une vidéo de montagne"},
			ActionNew, "vidéos · image : montagne"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			turn := say(t, NewSession(NewParser()), c.sentences...)
			require.Equal(t, c.action, turn.Action)
			require.Equal(t, c.summary, turn.Plan.Summary())
		})
	}
}

func TestSession_SelectAResult(t *testing.T) {
	cases := map[string]int{
		"le deuxième":             2,
		"ouvre le premier":        1,
		"montre-moi la troisième": 3,
		"le dernier":              -1,
		"ouvre le 4":              4,
		"numéro 7":                7,
	}
	for sentence, index := range cases {
		session := NewSession(NewParser())
		turn := say(t, session, "trouve-moi une photo de cuisine", sentence)
		require.Equal(t, ActionSelect, turn.Action, sentence)
		require.Equal(t, index, turn.Index, sentence)
		require.Equal(t, "photos · image : cuisine", turn.Plan.Summary(), sentence)
	}
}

func TestSession_MoreResults(t *testing.T) {
	for _, sentence := range []string{"la suite", "encore", "suivant", "d'autres"} {
		turn := say(t, NewSession(NewParser()), "trouve-moi une photo de cuisine", sentence)
		require.Equal(t, ActionMore, turn.Action, sentence)
		require.Equal(t, "photos · image : cuisine", turn.Plan.Summary(), sentence)
	}
}

func TestSession_Reset(t *testing.T) {
	session := NewSession(NewParser())
	turn := say(t, session, "trouve-moi une photo de cuisine", "nouvelle recherche")

	require.Equal(t, ActionReset, turn.Action)
	_, ok := session.Current()
	require.False(t, ok)

	// After a reset, a cue word no longer refers to anything.
	turn = say(t, session, "plutôt un salon")
	require.Equal(t, ActionNew, turn.Action)
	require.Equal(t, "image : un salon · texte : salon", turn.Plan.Summary())
}

func TestSession_NothingUnderstoodKeepsTheSearch(t *testing.T) {
	session := NewSession(NewParser())
	turn := say(t, session, "trouve-moi une photo de cuisine", "euh")

	require.Equal(t, ActionNone, turn.Action)
	require.Equal(t, "photos · image : cuisine", turn.Plan.Summary())

	require.Equal(t, ActionNone, NewSession(NewParser()).Interpret("", testNow).Action)
}

func TestSession_FirstSentenceIsNeverARefinement(t *testing.T) {
	turn := say(t, NewSession(NewParser()), "le deuxième PDF sans date")

	require.Equal(t, ActionNew, turn.Action)
	require.Equal(t, []Kind{KindPDF}, turn.Plan.Kinds)
}

func TestSession_RefiningDoesNotAlterThePreviousPlan(t *testing.T) {
	session := NewSession(NewParser())
	first := say(t, session, "trouve-moi une photo de cuisine")
	say(t, session, "avec un chien", "pas celle de l'an dernier")

	require.Equal(t, "photos · image : cuisine", first.Plan.Summary())
	require.Empty(t, first.Plan.ExcludeDates)
}

func TestSession_KeepsTheSecondSearch(t *testing.T) {
	turn := say(t, NewSession(NewParser()),
		"Trouve-moi le QR code que j'ai téléchargé il y a un mois, et tous ceux qui sont dans ma bibliothèque.")

	require.Equal(t, ActionNew, turn.Action)
	require.NotNil(t, turn.Then)
	require.Equal(t, "QR code · plus récents d'abord · tous les résultats", turn.Then.Summary())
}
