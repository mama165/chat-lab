package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WipeSummaryTest {
    private val full = WipeSummary(
        photos = 215,
        librarySize = 4L * 1024 * 1024,
        recents = listOf("gaufre", "wifi", "facture", "plage"),
        lastAnalysis = SimpleDate(2026, 11, 21),
        hasReport = true,
    )
    private val empty = WipeSummary(0, 0L, emptyList(), null, false)

    @Test
    fun linesUseThePersonsOwnNumbers() {
        val lines = full.lines()
        assertEquals("215 photos analysées · 4 Mo", lines[0].detail)
        assertEquals("gaufre, wifi, facture…", lines[1].detail)
        assertEquals("Analyse du 21 nov.", lines[2].detail)
        assertTrue(lines.none { it.empty })
        assertTrue(full.canWipe)
    }

    @Test
    fun emptyCaseSaysNoneAndCannotWipe() {
        val lines = empty.lines()
        assertEquals(listOf("aucune photo analysée", "aucune", "aucun"), lines.map { it.detail })
        assertTrue(lines.all { it.empty })
        assertFalse(empty.canWipe)
    }

    @Test
    fun singularForms() {
        val one = empty.copy(photos = 1, librarySize = 2048)
        assertEquals("1 photo analysée · 2 Ko", one.lines()[0].detail)
    }

    @Test
    fun anySingleThingIsEnoughToWipe() {
        assertTrue(empty.copy(recents = listOf("wifi")).canWipe)
        assertTrue(empty.copy(hasReport = true).canWipe)
        assertTrue(empty.copy(librarySize = 10).canWipe)
    }

    @Test
    fun onlyEmptyLinesAreGreyed() {
        val partial = empty.copy(photos = 10, librarySize = 1000)
        val lines = partial.lines()
        assertFalse(lines[0].empty)
        assertTrue(lines[1].empty)
    }

    @Test
    fun reportWithoutDate() {
        assertEquals("Dernière analyse", empty.copy(hasReport = true).lines()[2].detail)
    }

    @Test
    fun shortRecentListHasNoEllipsis() {
        assertEquals("gaufre, wifi", empty.copy(recents = listOf("gaufre", "wifi")).lines()[1].detail)
    }
}
