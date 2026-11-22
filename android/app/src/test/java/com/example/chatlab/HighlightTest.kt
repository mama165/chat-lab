package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighlightTest {
    @Test
    fun foldCharsKeepsTheLength() {
        val text = "Crème Brûlée"
        assertEquals(text.length, Highlight.foldChars(text).length)
        assertEquals("creme brulee", Highlight.foldChars(text))
    }

    @Test
    fun rangesFindTheWordIgnoringCaseAndAccents() {
        val text = "Pâte à Gaufres"
        val ranges = Highlight.ranges(text, "gaufre")
        assertEquals(listOf(7..12), ranges)
        assertEquals("Gaufre", text.substring(7, 13))
    }

    @Test
    fun rangesFindEveryOccurrenceAndEveryWord() {
        assertEquals(listOf(0..1, 6..7, 9..10), Highlight.ranges("ab cd ab ef", "ab ef"))
    }

    @Test
    fun overlappingRangesAreMerged() {
        assertEquals(listOf(0..5), Highlight.ranges("gaufre", "gauf ufre"))
    }

    @Test
    fun noMatchNoRange() {
        assertTrue(Highlight.ranges("facture", "wifi").isEmpty())
        assertTrue(Highlight.ranges("facture", "").isEmpty())
    }

    @Test
    fun shortTextIsKeptWhole() {
        val excerpt = Highlight.excerpt("Recette,  gaufre\nmaison", "gaufre")
        assertEquals("Recette, gaufre maison", excerpt.text)
        assertEquals(listOf(9..14), excerpt.marks)
    }

    @Test
    fun longTextIsCutAroundTheWord() {
        val text = "x".repeat(200) + " gaufre " + "y".repeat(200)
        val excerpt = Highlight.excerpt(text, "gaufre", max = 60)
        assertTrue(excerpt.text.startsWith("…"))
        assertTrue(excerpt.text.endsWith("…"))
        assertTrue(excerpt.text.contains("gaufre"))
        assertEquals(1, excerpt.marks.size)
        assertEquals("gaufre", excerpt.text.substring(excerpt.marks[0].first, excerpt.marks[0].last + 1))
    }

    @Test
    fun longTextWithoutTheWordShowsTheStart() {
        val excerpt = Highlight.excerpt("début " + "z".repeat(200), "wifi", max = 30)
        assertTrue(excerpt.text.startsWith("début"))
        assertTrue(excerpt.text.endsWith("…"))
        assertTrue(excerpt.marks.isEmpty())
    }
}
