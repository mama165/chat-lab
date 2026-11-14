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

func TestRun_SearchesALibrary(t *testing.T) {
	var out bytes.Buffer
	in := strings.NewReader("Trouve-moi le PDF où il est question d'un notaire.\nle premier\nnouvelle recherche\nles trois dernières vidéos\nle PDF sur les licornes\n")

	err := run([]string{"-now", "2026-11-14", "-library", "../../infrastructure/catalog/testdata/library.json"}, in, &out)

	require.NoError(t, err)
	require.Equal(t, []string{
		"> Recherche : PDF · texte : notaire",
		"  1 résultat",
		"  1. acte-vente.pdf — page 3 — « Maître Dupont, notaire à Lyon, atteste que l'acte a été reçu en son étude. »",
		"> Ouvrir : /storage/emulated/0/Download/acte-vente.pdf",
		"> Nouvelle recherche.",
		"> Recherche : vidéos · plus récents d'abord · 3 résultats",
		"  3 résultats",
		"  1. VID_0003.mp4 — un appartement|un couloir",
		"  2. VID_0004.mp4 — un concert sur une scène",
		"  3. VID_0002.mp4 — un paysage en montagne|un lac",
		"> Recherche : PDF · texte : licornes",
		"  Aucun résultat.",
		"> ",
	}, strings.Split(strings.TrimSuffix(out.String(), "\n"), "\n"))
}

func TestRun_UnreadableLibrary(t *testing.T) {
	var out bytes.Buffer
	require.Error(t, run([]string{"-library", filepath.Join(t.TempDir(), "absent.json"), "une rose"}, strings.NewReader(""), &out))
	bad := filepath.Join(t.TempDir(), "bad.json")
	require.NoError(t, os.WriteFile(bad, []byte(`{"pas": "une liste"}`), 0o600))
	require.Error(t, run([]string{"-library", bad, "une rose"}, strings.NewReader(""), &out))
}
