package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResultItemsTest {
    private fun matches(prefix: String, count: Int) = (1..count).map { TestData.match("$prefix$it", 0.1f) }

    private fun outcome(words: Int = 0, close: Int = 0, extra: Int = 0, remaining: Int = 0, limit: Boolean = false) =
        SearchOutcome(matches("w", words), matches("c", close), matches("e", extra), remaining, limit)

    @Test
    fun wordsThenLineThenResemblingPhotos() {
        val items = resultItems(outcome(words = 2, close = 2), "gaufre", -1)
        assertEquals(ResultItem.Label("Texte lu"), items[0])
        assertTrue(items[1] is ResultItem.Word)
        assertTrue(items[2] is ResultItem.Word)
        assertEquals(ResultItem.Line, items[3])
        assertEquals(ResultItem.Label("Ressemblantes"), items[4])
        assertEquals(2, items.count { it is ResultItem.Tile })
    }

    @Test
    fun noLineWithoutWords() {
        val items = resultItems(outcome(close = 1), "plage", -1)
        assertFalse(items.contains(ResultItem.Line))
        assertEquals(ResultItem.Label("Ressemblantes"), items[0])
    }

    @Test
    fun firstLinkAnnouncesTheFirstBatch() {
        val more = resultItems(outcome(close = 2, remaining = 20), "gaufre", -1).last()
        assertEquals(ResultItem.More("Voir des photos moins proches (6)"), more)
    }

    @Test
    fun laterLinksGiveTheCumulativeCount() {
        val items = resultItems(outcome(close = 2, extra = 6, remaining = 20), "gaufre", 0)
        assertEquals(ResultItem.Separator("Moins proches · 6"), items.first { it is ResultItem.Separator })
        assertEquals(ResultItem.More("Voir 6 photos de plus (12 sur 26)"), items.last())
    }

    @Test
    fun onlyTheLastBatchIsMarkedNew() {
        val items = resultItems(outcome(close = 1, extra = 12, remaining = 3), "gaufre", 6)
        val extraTiles = items.filterIsInstance<ResultItem.Tile>().drop(1)
        assertEquals(12, extraTiles.size)
        assertTrue(extraTiles.take(6).none { it.isNew })
        assertTrue(extraTiles.drop(6).all { it.isNew })
    }

    @Test
    fun nothingIsNewBeforeTheFirstTap() {
        val items = resultItems(outcome(close = 3, extra = 0, remaining = 9), "gaufre", -1)
        assertTrue(items.filterIsInstance<ResultItem.Tile>().none { it.isNew })
    }

    @Test
    fun endMessageAtTheFloor() {
        val end = resultItems(outcome(close = 1, extra = 9, remaining = 0), "gaufre", 6).last()
        assertEquals(
            ResultItem.End(
                "Plus de photos proches de « gaufre »",
                "Les suivantes ne ressemblent plus assez. Essayez d'autres mots, ou retirez un filtre.",
            ),
            end,
        )
    }

    @Test
    fun endMessageAtTheLimitPointsToTheFilters() {
        val end = resultItems(outcome(close = 1, extra = 30, remaining = 0, limit = true), "gaufre", 24).last() as ResultItem.End
        assertEquals("Pour aller plus loin, choisissez Large dans les filtres.", end.detail)
    }

    @Test
    fun noEndMessageWhenNoLessClosePhotoWasAsked() {
        val items = resultItems(outcome(words = 1, close = 1), "gaufre", -1)
        assertTrue(items.none { it is ResultItem.End || it is ResultItem.More })
    }

    @Test
    fun keysAreUnique() {
        val items = resultItems(outcome(words = 3, close = 4, extra = 6, remaining = 2), "gaufre", 0)
        assertEquals(items.size, items.map { it.key }.toSet().size)
    }

    @Test
    fun emptyOutcomeHasNoItems() {
        assertTrue(resultItems(SearchOutcome.EMPTY, "x", -1).isEmpty())
    }
}
