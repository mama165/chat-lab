package com.example.chatlab

import com.example.chatlab.TestData.TIME
import com.example.chatlab.TestData.hoursAgo
import com.example.chatlab.TestData.daysAgo
import com.example.chatlab.TestData.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultsTest {
    private fun ranked(entries: List<LibraryEntry>, scores: List<Float>): List<Match> =
        entries.zip(scores).map { (e, s) -> Match(e, s) }.sortedByDescending { it.score }

    private fun compute(
        entries: List<LibraryEntry>,
        query: String = "gaufre",
        ranked: List<Match> = emptyList(),
        filters: Filters = Filters(),
        shown: Int = 0,
    ) = SearchResults.compute(entries, query, ranked, filters, shown, TIME)

    @Test
    fun blankQueryGivesNothing() {
        val entries = listOf(entry("a", "gaufre"))
        assertTrue(compute(entries, query = "  ").isEmpty)
        assertEquals(SearchOutcome.EMPTY, compute(entries, query = ""))
    }

    @Test
    fun aDateAloneListsItsPhotosMostRecentFirst() {
        val later = entry("later", takenMs = hoursAgo(1))
        val earlier = entry("earlier", takenMs = hoursAgo(3))
        val old = entry("old", takenMs = daysAgo(3))
        val filters = Filters(date = DateFilter(preset = DatePreset.LAST_24H))
        val outcome = compute(listOf(earlier, old, later), query = "", filters = filters)
        assertEquals(listOf(later, earlier), outcome.close.map { it.entry })
        assertTrue(outcome.extra.isEmpty())
        assertFalse(outcome.hasMore)
    }

    @Test
    fun aDescriptionAloneListsTheSimilarPhotosMostRecentFirst() {
        val oldest = entry("oldest", takenMs = daysAgo(5))
        val newest = entry("newest", takenMs = hoursAgo(1))
        val middle = entry("middle", takenMs = daysAgo(2))
        // All three look alike enough: the score decides who comes, the date decides the order.
        val outcome = compute(
            listOf(oldest, newest, middle),
            query = "gâteau",
            ranked = ranked(listOf(oldest, newest, middle), listOf(0.30f, 0.29f, 0.28f)),
        )
        assertEquals(listOf(newest, middle, oldest), outcome.close.map { it.entry })
    }

    @Test
    fun aDateAndADescriptionListTheSimilarPhotosOfThatDateMostRecentFirst() {
        val first = entry("first", takenMs = hoursAgo(2))
        val second = entry("second", takenMs = hoursAgo(8))
        val other = entry("other", takenMs = daysAgo(4))
        val filters = Filters(date = DateFilter(preset = DatePreset.LAST_24H))
        val outcome = compute(
            listOf(second, other, first),
            query = "gâteau",
            ranked = ranked(listOf(second, other, first), listOf(0.30f, 0.30f, 0.29f)),
            filters = filters,
        )
        // The photo of four days ago is filtered out by the date, whatever its score.
        assertEquals(listOf(first, second), outcome.close.map { it.entry })
    }

    @Test
    fun aPhotoAppearsOnlyOnce() {
        val a = entry("a", "gaufre maison")
        val b = entry("b", "")
        val outcome = compute(listOf(a, b), ranked = listOf(Match(a, 0.30f), Match(a, 0.30f), Match(b, 0.29f)))
        assertEquals(listOf("a", "b"), outcome.close.map { it.entry.id })
        assertEquals(2, outcome.total)
    }

    @Test
    fun aPhotoWithoutSourceIsIdentifiedByItsPath() {
        val a = entry("a", "gaufre", source = "")
        assertEquals("/files/a.jpg", SearchResults.keyOf(Match(a, null)))
    }

    @Test
    fun filtersApplyToResemblance() {
        val inside = entry("in", "", indoor = true)
        val outside = entry("out", "", indoor = false)
        val outcome = compute(
            listOf(inside, outside),
            ranked = ranked(listOf(inside, outside), listOf(0.3f, 0.3f)),
            filters = Filters(place = Place.INDOOR),
        )
        assertEquals(listOf("in"), outcome.close.map { it.entry.id })
    }

    @Test
    fun dateFilterExcludesOldPhotos() {
        val recent = entry("recent", "", takenMs = hoursAgo(3))
        val old = entry("old", "", takenMs = daysAgo(40))
        val outcome = compute(
            listOf(recent, old),
            ranked = ranked(listOf(recent, old), listOf(0.3f, 0.3f)),
            filters = Filters(date = DateFilter(DatePreset.LAST_24H)),
        )
        assertEquals(listOf("recent"), outcome.close.map { it.entry.id })
    }

    @Test
    fun folderFilterKeepsOnlyThatFolder() {
        val a = entry("a", "", folder = "Camera")
        val b = entry("b", "", folder = "Screenshots")
        val outcome = compute(
            listOf(a, b),
            ranked = ranked(listOf(a, b), listOf(0.3f, 0.3f)),
            filters = Filters(folders = setOf("Screenshots")),
        )
        assertEquals(listOf("b"), outcome.close.map { it.entry.id })
    }

    @Test
    fun resemblanceSetsTheStartingPoint() {
        val photos = (1..3).map { entry("p$it") }
        val scores = listOf(0.12f, 0.10f, 0.09f)
        val r = ranked(photos, scores)
        assertEquals(1, compute(photos, ranked = r, filters = Filters(resemblance = Resemblance.CLOSE)).close.size)
        assertEquals(3, compute(photos, ranked = r, filters = Filters(resemblance = Resemblance.NORMAL)).close.size)
    }

    @Test
    fun lessCloseComeInBatchesOfSix() {
        val photos = (1..20).map { entry("p$it") }
        val scores = listOf(0.20f) + (1..19).map { 0.15f - it * 0.005f }
        val r = ranked(photos, scores)
        val start = compute(photos, ranked = r)
        assertEquals(1, start.close.size)
        assertEquals(0, start.extra.size)
        assertEquals(6, start.nextBatch)
        assertTrue(start.hasMore)

        val once = compute(photos, ranked = r, shown = SearchResults.nextShown(start))
        assertEquals(6, once.extra.size)
        val twice = compute(photos, ranked = r, shown = SearchResults.nextShown(once))
        assertEquals(12, twice.extra.size)
        assertEquals(1 + 12, twice.total)
    }

    @Test
    fun lessCloseAreInDecreasingOrderAndBelowTheCloseOnes() {
        val photos = (1..5).map { entry("p$it") }
        val r = ranked(photos, listOf(0.30f, 0.20f, 0.10f, 0.08f, 0.05f))
        val outcome = compute(photos, ranked = r, shown = 6)
        assertEquals(listOf("p1"), outcome.close.map { it.entry.id })
        assertEquals(listOf("p2", "p3", "p4", "p5"), outcome.extra.map { it.entry.id })
        assertFalse(outcome.hasMore)
    }

    @Test
    fun lastBatchCanBeSmaller() {
        val photos = (1..10).map { entry("p$it") }
        val r = ranked(photos, listOf(0.3f) + List(9) { 0.1f })
        val outcome = compute(photos, ranked = r, shown = 6)
        assertEquals(3, outcome.remaining)
        assertEquals(3, outcome.nextBatch)
    }

    @Test
    fun scoresUnderTheFloorAreNeverOffered() {
        val photos = (1..3).map { entry("p$it") }
        val r = ranked(photos, listOf(0.3f, 0.02f, 0.01f))
        val outcome = compute(photos, ranked = r)
        assertFalse(outcome.hasMore)
        assertEquals(0, outcome.remaining)
        assertFalse(outcome.limitReached)
    }

    @Test
    fun stopsAtThirtyLessClosePhotos() {
        val photos = (1..50).map { entry("p$it") }
        val r = ranked(photos, listOf(0.5f) + List(49) { 0.2f })
        val full = compute(photos, ranked = r, shown = 100)
        assertEquals(MoreResults.MAX_EXTRA, full.extra.size)
        assertFalse(full.hasMore)
        assertTrue(full.limitReached)
        val notYet = compute(photos, ranked = r, shown = 24)
        assertFalse(notYet.limitReached)
        assertEquals(6, notYet.remaining)
    }

    @Test
    fun noLimitMessageWhenFewerThanThirtyExist() {
        val photos = (1..10).map { entry("p$it") }
        val r = ranked(photos, listOf(0.5f) + List(9) { 0.2f })
        assertFalse(compute(photos, ranked = r, shown = 30).limitReached)
    }

    @Test
    fun withoutDescriptionThereIsNoPhoto() {
        val outcome = compute(listOf(entry("a", "gaufre")), ranked = emptyList())
        assertTrue(outcome.isEmpty)
        assertFalse(outcome.hasMore)
    }

    @Test
    fun negativeShownIsTreatedAsZero() {
        val photos = (1..5).map { entry("p$it") }
        val r = ranked(photos, listOf(0.5f, 0.2f, 0.2f, 0.2f, 0.2f))
        assertEquals(0, compute(photos, ranked = r, shown = -3).extra.size)
    }
}
