package query

import (
	"testing"
	"time"

	"github.com/stretchr/testify/require"
)

func TestRelativeDates(t *testing.T) {
	// testNow is Saturday 14 November 2026. "to" is the first day excluded.
	cases := []struct {
		said     string
		from, to string
		approx   bool
	}{
		{"hier", "2026-11-13", "2026-11-14", false},
		{"avant-hier", "2026-11-12", "2026-11-13", false},
		{"aujourd'hui", "2026-11-14", "2026-11-15", false},
		{"ce matin", "2026-11-14", "2026-11-15", false},
		{"il y a un mois", "2026-09-29", "2026-10-25", true},
		{"il y a 3 jours", "2026-11-10", "2026-11-13", true},
		{"il y a une quinzaine de jours", "2026-10-23", "2026-11-05", true},
		{"y a deux semaines", "2026-10-24", "2026-11-05", true},
		{"voilà 2 mois", "2026-08-15", "2026-10-05", true},
		{"ces 6 derniers mois", "2026-04-26", "2026-11-15", true},
		{"les 3 dernières semaines", "2026-10-22", "2026-11-15", true},
		{"ces derniers jours", "2026-11-06", "2026-11-15", true},
		{"depuis 2 semaines", "2026-10-30", "2026-11-15", true},
		{"depuis hier", "2026-11-13", "2026-11-15", false},
		{"depuis janvier", "2026-01-01", "2026-11-15", false},
		{"depuis mars 2024", "2024-03-01", "2026-11-15", false},
		{"depuis 2023", "2023-01-01", "2026-11-15", false},
		{"la semaine dernière", "2026-11-02", "2026-11-09", false},
		{"le mois dernier", "2026-10-01", "2026-11-01", false},
		{"l'an dernier", "2025-01-01", "2026-01-01", false},
		{"l'année passée", "2025-01-01", "2026-01-01", false},
		{"le week-end dernier", "2026-11-07", "2026-11-09", false},
		{"ce week-end", "2026-11-14", "2026-11-15", false},
		{"cette semaine", "2026-11-09", "2026-11-15", false},
		{"ce mois-ci", "2026-11-01", "2026-11-15", false},
		{"cette année", "2026-01-01", "2026-11-15", false},
		{"en mars", "2026-03-01", "2026-04-01", false},
		// December has not come yet this year: it is last year's.
		{"en décembre", "2025-12-01", "2026-01-01", false},
		{"en mars 2024", "2024-03-01", "2024-04-01", false},
		{"du mois d'août", "2026-08-01", "2026-09-01", false},
		{"mars dernier", "2026-03-01", "2026-04-01", false},
		{"novembre dernier", "2025-11-01", "2025-12-01", false},
		{"en 2024", "2024-01-01", "2025-01-01", false},
		{"de 2019", "2019-01-01", "2020-01-01", false},
		{"l'été dernier", "2026-06-01", "2026-10-01", true},
		{"cet été", "2026-06-01", "2026-10-01", true},
		{"été 2024", "2024-06-01", "2024-10-01", true},
		{"l'hiver dernier", "2025-12-01", "2026-04-01", true},
		{"à Noël", "2025-12-20", "2025-12-29", false},
		{"noël 2023", "2023-12-20", "2023-12-29", false},
		{"avant 2023", "1990-01-01", "2023-01-01", false},
		{"avant mars", "1990-01-01", "2026-03-01", false},
		{"après mars", "2026-04-01", "2026-11-15", false},
		{"entre janvier et mars", "2026-01-01", "2026-04-01", false},
		{"entre 2022 et 2024", "2022-01-01", "2025-01-01", false},
	}
	for _, c := range cases {
		t.Run(c.said, func(t *testing.T) {
			toks := tokenize(c.said)
			found := extractDates(c.said, toks, testNow)
			require.Len(t, found, 1)
			require.Equal(t, c.from, found[0].From.Format(time.DateOnly), "from")
			require.Equal(t, c.to, found[0].To.Format(time.DateOnly), "to")
			require.Equal(t, c.approx, found[0].Approx, "approx")
			require.NotEmpty(t, found[0].Label)
			require.Equal(t, c.said[found[0].Origin.Start:found[0].Origin.End], found[0].Origin.Text)
		})
	}
}

func TestRelativeDates_NotADate(t *testing.T) {
	for _, said := range []string{
		"un homme qui a été photographié", // "été" is the verb here
		"il y a un chien",
		"une rose",
		"le mois",
		"avant ça",
		"photo numéro 12",
		"en décembre 2030", // entirely in the future: no file can match
	} {
		found := extractDates(said, tokenize(said), testNow)
		require.Empty(t, found, said)
	}
}

func TestRelativeDates_FollowTheClock(t *testing.T) {
	said := "en mars"
	february := time.Date(2026, time.February, 10, 9, 0, 0, 0, time.UTC)

	found := extractDates(said, tokenize(said), february)

	require.Len(t, found, 1)
	require.Equal(t, "2025-03-01", found[0].From.Format(time.DateOnly))
}

func TestRelativeDates_KeepTheTimeZoneOfTheClock(t *testing.T) {
	paris := time.FixedZone("CET", 3600)
	// 00:30 in Paris on the 14th is still the 13th in UTC.
	now := time.Date(2026, time.November, 14, 0, 30, 0, 0, paris)

	found := extractDates("hier", tokenize("hier"), now)

	require.Len(t, found, 1)
	require.Equal(t, time.Date(2026, time.November, 13, 0, 0, 0, 0, paris), found[0].From)
}

func TestFormatRange(t *testing.T) {
	day := func(s string) time.Time {
		parsed, err := time.Parse(time.DateOnly, s)
		require.NoError(t, err)
		return parsed
	}
	cases := []struct{ from, to, want string }{
		{"2026-11-13", "2026-11-14", "le 13 novembre 2026"},
		{"2026-03-01", "2026-04-01", "mars 2026"},
		{"2024-01-01", "2025-01-01", "2024"},
		{"2026-04-26", "2026-11-15", "depuis le 26 avril 2026"},
		{"2026-09-29", "2026-10-25", "du 29 septembre au 24 octobre 2026"},
		{"2025-12-20", "2026-01-05", "du 20 décembre 2025 au 4 janvier 2026"},
		{"1990-01-01", "2023-01-01", "avant le 1er janvier 2023"},
	}
	for _, c := range cases {
		require.Equal(t, c.want, formatRange(day(c.from), day(c.to), testNow))
	}
}
