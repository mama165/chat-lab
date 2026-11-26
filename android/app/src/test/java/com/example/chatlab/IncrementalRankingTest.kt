package com.example.chatlab

import com.example.chatlab.TestData.entry
import org.junit.Assert.assertEquals
import org.junit.Test

class IncrementalRankingTest {
    @Test
    fun withTheSameSentenceOnlyTheNewPhotosAreScored() {
        val a = entry("a")
        val b = entry("b")
        val scored = setOf("a")
        assertEquals(listOf(b), IncrementalRanking.photosToScore(listOf(a, b), scored, sameQuery = true))
    }

    @Test
    fun withANewSentenceEveryPhotoIsScored() {
        val a = entry("a")
        val b = entry("b")
        assertEquals(listOf(a, b), IncrementalRanking.photosToScore(listOf(a, b), setOf("a"), sameQuery = false))
    }

    @Test
    fun theMergeKeepsTheBestFirst() {
        val a = entry("a")
        val b = entry("b")
        val c = entry("c")
        val ranked = listOf(Match(a, 0.30f), Match(b, 0.10f))
        val merged = IncrementalRanking.merge(ranked, listOf(Match(c, 0.20f)))
        assertEquals(listOf("a", "c", "b"), merged.map { it.entry.id })
    }

    @Test
    fun aPhotoWithoutScoreGoesLast() {
        val a = entry("a")
        val merged = IncrementalRanking.merge(listOf(Match(a, null)), listOf(Match(entry("b"), 0.05f)))
        assertEquals(listOf("b", "a"), merged.map { it.entry.id })
    }
}
