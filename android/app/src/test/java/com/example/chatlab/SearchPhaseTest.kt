package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchPhaseTest {
    private val vector = FloatArray(4) { 0.5f }
    private val otherVector = FloatArray(4) { 0.5f }

    @Test
    fun `a blank query is never pending`() {
        assertFalse(rankingPending(queryBlank = true, modelFailed = false, vector = null, rankedFor = null))
    }

    @Test
    fun `a failed model is never pending, the text search answers`() {
        assertFalse(rankingPending(queryBlank = false, modelFailed = true, vector = null, rankedFor = null))
    }

    @Test
    fun `no vector yet means the search is pending`() {
        assertTrue(rankingPending(queryBlank = false, modelFailed = false, vector = null, rankedFor = null))
    }

    @Test
    fun `a vector whose ranking is not done yet is pending`() {
        assertTrue(rankingPending(queryBlank = false, modelFailed = false, vector = vector, rankedFor = null))
    }

    @Test
    fun `a ranking that belongs to an earlier vector is still pending`() {
        assertTrue(rankingPending(queryBlank = false, modelFailed = false, vector = vector, rankedFor = otherVector))
    }

    @Test
    fun `the ranking of the current vector ends the search`() {
        assertFalse(rankingPending(queryBlank = false, modelFailed = false, vector = vector, rankedFor = vector))
    }

    @Test
    fun `a pending search shows the ranking, then the limit message after the limit`() {
        assertEquals(Waiting.RANKING, waitingFor(pending = true, timedOut = false))
        assertEquals(Waiting.TIMED_OUT, waitingFor(pending = true, timedOut = true))
    }

    @Test
    fun `a finished search never shows the waiting state, even if the timer was set`() {
        assertEquals(Waiting.NONE, waitingFor(pending = false, timedOut = false))
        assertEquals(Waiting.NONE, waitingFor(pending = false, timedOut = true))
    }

    @Test
    fun `the limit is ten seconds`() {
        assertEquals(10_000L, SEARCH_LIMIT_MS)
    }
}
