package main

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/stretchr/testify/require"
)

func TestRun_OneSentence(t *testing.T) {
	var out bytes.Buffer
	err := run([]string{"-now", "2026-11-14", "Trouve-moi le PDF de ces 6 derniers mois qui contient la notice d'un lave-vaisselle."}, strings.NewReader(""), &out)

	require.NoError(t, err)
	require.Equal(t, "Recherche : PDF · depuis le 26 avril 2026 · texte : notice, lave-vaisselle\n", out.String())
}

func TestRun_Conversation(t *testing.T) {
	var out bytes.Buffer
	in := strings.NewReader("trouve-moi une photo de cuisine\npas celle de l'an dernier\net en vidéo ?\nle deuxième\nla suite\nnouvelle recherche\n???\n")

	require.NoError(t, run([]string{"-now", "2026-11-14"}, in, &out))

	require.Equal(t, []string{
		"> Recherche : photos · image : cuisine",
		"> Recherche affinée : photos · sauf 2025 · image : cuisine",
		"> Recherche affinée : vidéos · sauf 2025 · image : cuisine",
		"> Ouvrir le résultat n° 2.",
		"> Suite des résultats de : vidéos · sauf 2025 · image : cuisine",
		"> Nouvelle recherche.",
		"> Je n'ai pas compris ce qu'il faut chercher.",
		"> ",
	}, strings.Split(strings.TrimSuffix(out.String(), "\n"), "\n"))
}

func TestRun_JSONAndPersonalLexicon(t *testing.T) {
	lexicon := filepath.Join(t.TempDir(), "lexicon.json")
	require.NoError(t, os.WriteFile(lexicon, []byte(`[{"phrase": "mon assureur", "value": "assurance", "alternatives": ["maif"]}]`), 0o600))
	var out bytes.Buffer

	err := run([]string{"-json", "-lexicon", lexicon, "le PDF de mon assureur"}, strings.NewReader(""), &out)

	require.NoError(t, err)
	require.Contains(t, out.String(), "Recherche : PDF · texte : assurance\n")
	require.Contains(t, out.String(), `"alternatives": [`)
	require.Contains(t, out.String(), `"maif"`)
}

func TestRun_BadArguments(t *testing.T) {
	var out bytes.Buffer
	require.Error(t, run([]string{"-now", "hier", "une rose"}, strings.NewReader(""), &out))
	require.Error(t, run([]string{"-lexicon", filepath.Join(t.TempDir(), "absent.json"), "une rose"}, strings.NewReader(""), &out))
}
