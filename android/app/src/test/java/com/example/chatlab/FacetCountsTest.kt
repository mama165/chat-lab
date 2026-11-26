package com.example.chatlab

import com.example.chatlab.TestData.TIME
import com.example.chatlab.TestData.daysAgo
import com.example.chatlab.TestData.entry
import com.example.chatlab.TestData.hoursAgo
import com.example.chatlab.TestData.minutesAgo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FacetCountsTest {
    private val entries = listOf(
        entry("a", "gaufre", indoor = true, faces = 0, folder = "Camera", mime = FileSniffer.JPEG, takenMs = minutesAgo(30)),
        entry("b", "gaufre", indoor = true, faces = 1, folder = "Camera", mime = FileSniffer.JPEG, takenMs = hoursAgo(5)),
        entry("c", "gaufre", indoor = false, faces = 2, folder = "Screenshots", mime = FileSniffer.PNG, takenMs = daysAgo(3)),
        entry("d", "", indoor = null, faces = 0, folder = "Camera", mime = FileSniffer.JPEG, takenMs = daysAgo(100)),
        entry("e", "autre", indoor = true, faces = 0, folder = "Screenshots", mime = FileSniffer.PNG, takenMs = 0L),
    )

    /** The photos that look like the query: here, the ones whose text contains it, all equally close. */
    private fun similar(list: List<LibraryEntry>, query: String): List<Match> =
        list.filter { it.text.contains(query) }.map { Match(it, 0.3f) }

    private fun facets(query: String = "gaufre", filters: Filters = Filters(), removed: Set<String> = emptySet()) =
        FacetCounts.compute(entries, query, similar(entries, query), filters, removed, TIME)

    @Test
    fun theTotalIsTheNumberOfResults() {
        assertEquals(3, facets().total)
    }

    @Test
    fun dateCountsAreCumulativeWindows() {
        val counts = facets().datePresets
        assertEquals(3, counts.getValue(DatePreset.ALL))
        assertEquals(1, counts.getValue(DatePreset.LAST_HOUR))
        assertEquals(2, counts.getValue(DatePreset.LAST_24H)) // a (il y a 30 min) et b (il y a 5 h)
        assertEquals(3, counts.getValue(DatePreset.LAST_7D)) // + c (il y a 3 jours)
        assertEquals(3, counts.getValue(DatePreset.LAST_30D))
        assertEquals(3, counts.getValue(DatePreset.LAST_12M))
    }

    @Test
    fun placeCountsIgnoreThePlaceFilterItself() {
        val counts = facets(filters = Filters(place = Place.INDOOR)).places
        assertEquals(3, counts.getValue(Place.ALL))
        assertEquals(2, counts.getValue(Place.INDOOR))
        assertEquals(1, counts.getValue(Place.OUTDOOR))
    }

    @Test
    fun otherFiltersStillApplyToEachCount() {
        val counts = facets(filters = Filters(folders = setOf("Camera"))).places
        assertEquals(2, counts.getValue(Place.ALL))
        assertEquals(2, counts.getValue(Place.INDOOR))
        assertEquals(0, counts.getValue(Place.OUTDOOR))
    }

    @Test
    fun peopleCounts() {
        val counts = facets().people
        assertEquals(3, counts.getValue(People.ANY))
        assertEquals(1, counts.getValue(People.NONE))
        assertEquals(1, counts.getValue(People.ONE))
        assertEquals(1, counts.getValue(People.MANY))
    }

    @Test
    fun textCountsOverAQueryAreAlwaysWithText() {
        // A search by word only finds photos with text.
        val counts = facets().texts
        assertEquals(3, counts.getValue(TextFilter.WITH))
        assertEquals(0, counts.getValue(TextFilter.WITHOUT))
    }

    @Test
    fun withoutAQueryEveryPhotoIsCounted() {
        val counts = facets(query = "").texts
        assertEquals(5, counts.getValue(TextFilter.ANY))
        assertEquals(4, counts.getValue(TextFilter.WITH))
        assertEquals(1, counts.getValue(TextFilter.WITHOUT))
        assertEquals(5, facets(query = "").total)
    }

    @Test
    fun folderCounts() {
        val counts = facets().folders
        assertEquals(mapOf("Camera" to 2, "Screenshots" to 1), counts)
    }

    @Test
    fun typeCountsOnlyListTypesFoundAndSupported() {
        val counts = facets().types
        assertEquals(mapOf(FileSniffer.JPEG to 2, FileSniffer.PNG to 1), counts)
    }

    @Test
    fun closeUpCountsWhatTheToggleWouldKeep() {
        val withCloseUp = entries.map { if (it.id == "b") it.copy(closeUp = true) else it }
        val facets = FacetCounts.compute(withCloseUp, "gaufre", similar(withCloseUp, "gaufre"), Filters(), emptySet(), TIME)
        assertEquals(1, facets.closeUp)
    }

    @Test
    fun aCustomRangeHasItsOwnCount() {
        assertNull(facets().customRange)
        val range = DateRange(TIME.today.plusDays(-4, TIME.zone), TIME.today)
        val facets = facets(filters = Filters(date = DateFilter(range = range)))
        assertEquals(3, facets.customRange) // a, b (aujourd'hui) et c (il y a 3 jours)
        val shortRange = DateRange(TIME.today.plusDays(-1, TIME.zone), TIME.today)
        assertEquals(2, facets(filters = Filters(date = DateFilter(range = shortRange))).customRange)
    }

    @Test
    fun removedPhotosAreNotCounted() {
        val counts = facets(removed = setOf("content://photos/a"))
        assertEquals(2, counts.total)
        assertEquals(0, counts.datePresets.getValue(DatePreset.LAST_HOUR))
    }

    @Test
    fun resemblanceCountsExistForEveryLevel() {
        assertEquals(Resemblance.entries.toSet(), facets().resemblance.keys)
    }

    @Test
    fun aChoiceThatGivesNothingIsCountedZero() {
        assertEquals(0, facets(filters = Filters(date = DateFilter(DatePreset.LAST_HOUR), place = Place.OUTDOOR)).total)
    }
}
