package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerRowTest {
    @Test
    fun theChoiceStaysBetweenOneAndFourPhotos() {
        assertEquals(1, PerRow.clamp(0))
        assertEquals(1, PerRow.clamp(-3))
        assertEquals(4, PerRow.clamp(5))
        assertEquals(2, PerRow.clamp(2))
    }

    @Test
    fun theDefaultIsThreePhotosAPerRow() {
        assertEquals(3, PerRow.DEFAULT)
        assertTrue(PerRow.DEFAULT in PerRow.MIN..PerRow.MAX)
    }

    @Test
    fun theControlAppearsOnlyWithResultsToRearrange() {
        assertTrue(perRowControlShown("sport", hasResults = true, loading = false, ranking = false))
    }

    @Test
    fun theControlIsHiddenWithoutASentence() {
        assertFalse(perRowControlShown("", hasResults = true, loading = false, ranking = false))
        assertFalse(perRowControlShown("   ", hasResults = true, loading = false, ranking = false))
    }

    @Test
    fun theControlIsHiddenWithoutResults() {
        assertFalse(perRowControlShown("sport", hasResults = false, loading = false, ranking = false))
    }

    @Test
    fun theControlIsHiddenWhileTheLibraryLoadsOrTheSearchRuns() {
        assertFalse(perRowControlShown("sport", hasResults = true, loading = true, ranking = false))
        assertFalse(perRowControlShown("sport", hasResults = true, loading = false, ranking = true))
    }
}
