package com.example.chatlab

import com.example.chatlab.TestData.NOW
import com.example.chatlab.TestData.TIME
import com.example.chatlab.TestData.UTC
import com.example.chatlab.TestData.daysAgo
import com.example.chatlab.TestData.entry
import com.example.chatlab.TestData.hoursAgo
import com.example.chatlab.TestData.minutesAgo
import com.example.chatlab.TestData.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FiltersTest {
    private fun matches(filters: Filters, entry: LibraryEntry) = filters.matches(entry, TIME)
    private fun at(takenMs: Long) = entry("p", takenMs = takenMs)

    @Test
    fun defaultFiltersAreInactive() {
        val filters = Filters()
        assertTrue(filters.isDefault)
        assertEquals(0, filters.activeCount)
        assertTrue(filters.chips().isEmpty())
        assertEquals(Resemblance.NORMAL, filters.resemblance)
    }

    @Test
    fun everyCategoryCountsOnce() {
        assertEquals(1, Filters(place = Place.INDOOR).activeCount)
        assertEquals(1, Filters(folders = setOf("Camera", "Screenshots")).activeCount)
        assertEquals(1, Filters(types = setOf(FileSniffer.JPEG, FileSniffer.PNG)).activeCount)
        assertEquals(2, Filters(place = Place.INDOOR, date = DateFilter(DatePreset.LAST_24H)).activeCount)
        val all = Filters(
            date = DateFilter(DatePreset.LAST_7D), place = Place.OUTDOOR, people = People.MANY, closeUp = true,
            text = TextFilter.WITH, folders = setOf("Camera"), types = setOf(FileSniffer.JPEG), resemblance = Resemblance.WIDE,
        )
        assertEquals(8, all.activeCount)
    }

    @Test
    fun chipsFollowThePageOrderWithLabels() {
        val filters = Filters(
            date = DateFilter(DatePreset.LAST_24H), place = Place.INDOOR, people = People.NONE, closeUp = true,
            text = TextFilter.WITH, folders = setOf("Screenshots", "Camera"), types = setOf(FileSniffer.PNG, FileSniffer.JPEG),
            resemblance = Resemblance.CLOSE,
        )
        assertEquals(
            listOf(
                "24 dernières heures", "Intérieur", "Sans personne", "Gros plan", "Avec texte",
                "Camera, Screenshots", "JPG, PNG", "Ressemblance proche",
            ),
            filters.chips(TIME).map { it.label },
        )
    }

    @Test
    fun customDatesAreWrittenAsARange() {
        val range = DateRange(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22))
        assertEquals("9 – 22 nov.", Filters(date = DateFilter(range = range)).chips(TIME).single().label)
    }

    @Test
    fun customDatesWithADayPartMentionIt() {
        val range = DateRange(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22), DayPart.MORNING)
        assertEquals("9 – 22 nov. · Matin", Filters(date = DateFilter(range = range)).chips(TIME).single().label)
    }

    @Test
    fun removingAChipOnlyResetsThatCategory() {
        val filters = Filters(place = Place.INDOOR, date = DateFilter(DatePreset.LAST_7D), folders = setOf("Camera"))
        val withoutPlace = filters.chips(TIME).first { it.label == "Intérieur" }.without
        assertEquals(Place.ALL, withoutPlace.place)
        assertEquals(DatePreset.LAST_7D, withoutPlace.date.preset)
        assertEquals(setOf("Camera"), withoutPlace.folders)
        assertEquals(2, withoutPlace.activeCount)
        assertTrue(filters.chips(TIME).fold(filters) { acc, chip -> acc.chips(TIME).firstOrNull { it.label == chip.label }?.without ?: acc }.isDefault)
    }

    @Test
    fun peopleChipLabels() {
        assertEquals("1 personne", Filters(people = People.ONE).chips().single().label)
        assertEquals("2 personnes et plus", Filters(people = People.MANY).chips().single().label)
        assertEquals("Sans personne", Filters(people = People.NONE).chips().single().label)
    }

    @Test
    fun placeLabels() {
        assertEquals("Partout", Filters.placeLabel(Place.ALL))
        assertEquals("Extérieur", Filters.placeLabel(Place.OUTDOOR))
    }

    @Test
    fun matchesPlaceAndPeople() {
        val inside = entry("a", indoor = true, faces = 0)
        assertTrue(matches(Filters(place = Place.INDOOR), inside))
        assertFalse(matches(Filters(place = Place.OUTDOOR), inside))
        assertTrue(matches(Filters(people = People.NONE), inside))
        assertFalse(matches(Filters(people = People.ONE), inside))
        assertTrue(matches(Filters(people = People.MANY), entry("b", faces = 3)))
        assertFalse(matches(Filters(people = People.ONE), entry("c", faces = 3)))
        assertTrue(matches(Filters(people = People.ONE), entry("d", faces = 1)))
    }

    @Test
    fun matchesCloseUpOnlyWhenAsked() {
        assertTrue(matches(Filters(), entry("a", closeUp = false)))
        assertFalse(matches(Filters(closeUp = true), entry("a", closeUp = false)))
        assertTrue(matches(Filters(closeUp = true), entry("b", closeUp = true)))
    }

    @Test
    fun matchesText() {
        val with = entry("a", text = "FACTURE")
        val without = entry("b", text = "")
        val blank = entry("c", text = "   ")
        assertTrue(matches(Filters(text = TextFilter.WITH), with))
        assertFalse(matches(Filters(text = TextFilter.WITH), without))
        assertFalse(matches(Filters(text = TextFilter.WITH), blank))
        assertTrue(matches(Filters(text = TextFilter.WITHOUT), without))
        assertTrue(matches(Filters(text = TextFilter.WITHOUT), blank))
        assertFalse(matches(Filters(text = TextFilter.WITHOUT), with))
    }

    @Test
    fun matchesFoldersAndTypesWithSeveralChoices() {
        val camera = entry("a", folder = "Camera", mime = FileSniffer.JPEG)
        val shots = entry("b", folder = "Screenshots", mime = FileSniffer.PNG)
        val old = entry("c", folder = "", mime = "")
        assertTrue(matches(Filters(folders = setOf("Camera", "Screenshots")), camera))
        assertTrue(matches(Filters(folders = setOf("Camera", "Screenshots")), shots))
        assertFalse(matches(Filters(folders = setOf("Camera")), shots))
        assertFalse(matches(Filters(folders = setOf("Camera")), old))
        assertTrue(matches(Filters(types = setOf(FileSniffer.PNG)), shots))
        assertFalse(matches(Filters(types = setOf(FileSniffer.PNG)), camera))
        assertTrue(matches(Filters(), old))
    }

    @Test
    fun theLastHourIncludesTheBoundaryAndExcludesJustAfter() {
        val filters = Filters(date = DateFilter(DatePreset.LAST_HOUR))
        assertTrue(matches(filters, at(minutesAgo(5))))
        assertTrue(matches(filters, at(minutesAgo(60))))
        assertFalse(matches(filters, at(minutesAgo(61))))
        assertFalse(matches(filters, at(hoursAgo(5))))
    }

    @Test
    fun theLast24HoursAndLastWeekAndLastMonth() {
        assertTrue(matches(Filters(date = DateFilter(DatePreset.LAST_24H)), at(hoursAgo(23))))
        assertFalse(matches(Filters(date = DateFilter(DatePreset.LAST_24H)), at(hoursAgo(25))))
        assertTrue(matches(Filters(date = DateFilter(DatePreset.LAST_7D)), at(daysAgo(6))))
        assertFalse(matches(Filters(date = DateFilter(DatePreset.LAST_7D)), at(daysAgo(8))))
        assertTrue(matches(Filters(date = DateFilter(DatePreset.LAST_30D)), at(daysAgo(29))))
        assertFalse(matches(Filters(date = DateFilter(DatePreset.LAST_30D)), at(daysAgo(31))))
    }

    @Test
    fun theLastTwelveMonthsAreCalendarMonths() {
        val filters = Filters(date = DateFilter(DatePreset.LAST_12M))
        assertTrue(matches(filters, at(utc(2025, 11, 23))))
        assertFalse(matches(filters, at(utc(2025, 11, 21))))
    }

    @Test
    fun aPhotoAMinuteInTheFutureIsStillRecent() {
        val filters = Filters(date = DateFilter(DatePreset.LAST_HOUR))
        assertTrue(matches(filters, at(NOW + 30_000L)))
        assertFalse(matches(filters, at(NOW + 5 * 60_000L)))
    }

    @Test
    fun anUndatedPhotoOnlyPassesWhenNoDateIsAsked() {
        val undated = at(0L)
        assertTrue(matches(Filters(), undated))
        assertFalse(matches(Filters(date = DateFilter(DatePreset.LAST_12M)), undated))
        assertFalse(matches(Filters(date = DateFilter(range = DateRange(SimpleDate(2026, 1, 1), SimpleDate(2026, 12, 31)))), undated))
    }

    @Test
    fun customDatesAreInclusiveOfBothEnds() {
        val filters = Filters(date = DateFilter(range = DateRange(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22))))
        assertTrue(matches(filters, at(utc(2026, 11, 9, 0, 0))))
        assertTrue(matches(filters, at(utc(2026, 11, 22, 23, 59))))
        assertFalse(matches(filters, at(utc(2026, 11, 8, 23, 59))))
        assertFalse(matches(filters, at(utc(2026, 11, 23, 0, 0))))
    }

    @Test
    fun customDatesWinOverThePreset() {
        val range = DateRange(SimpleDate(2026, 1, 1), SimpleDate(2026, 1, 31))
        val filters = Filters(date = DateFilter(DatePreset.LAST_HOUR, range))
        assertTrue(matches(filters, at(utc(2026, 1, 15))))
        assertFalse(matches(filters, at(minutesAgo(5))))
    }

    @Test
    fun dayPartsSplitTheDay() {
        fun part(p: DayPart, hour: Int, minute: Int = 0) =
            Filters(date = DateFilter(range = DateRange(SimpleDate(2026, 11, 1), SimpleDate(2026, 11, 30), p)))
                .matches(at(utc(2026, 11, 10, hour, minute)), TIME)
        assertTrue(part(DayPart.MORNING, 6))
        assertTrue(part(DayPart.MORNING, 11, 59))
        assertFalse(part(DayPart.MORNING, 12))
        assertTrue(part(DayPart.AFTERNOON, 12))
        assertFalse(part(DayPart.AFTERNOON, 18))
        assertTrue(part(DayPart.EVENING, 18))
        assertTrue(part(DayPart.EVENING, 21, 59))
        assertFalse(part(DayPart.EVENING, 22))
        assertTrue(part(DayPart.ALL, 3))
    }

    @Test
    fun theNightCrossesMidnight() {
        fun night(hour: Int, minute: Int = 0) =
            Filters(date = DateFilter(range = DateRange(SimpleDate(2026, 11, 1), SimpleDate(2026, 11, 30), DayPart.NIGHT)))
                .matches(at(utc(2026, 11, 10, hour, minute)), TIME)
        assertTrue(night(22))
        assertTrue(night(23, 59))
        assertTrue(night(0))
        assertTrue(night(5, 59))
        assertFalse(night(6))
        assertFalse(night(14))
        assertFalse(night(21, 59))
    }

    @Test(expected = IllegalArgumentException::class)
    fun aRangeCannotEndBeforeItStarts() {
        DateRange(SimpleDate(2026, 11, 22), SimpleDate(2026, 11, 9))
    }

    @Test
    fun resemblanceDoesNotFilterPhotosItself() {
        assertTrue(matches(Filters(resemblance = Resemblance.CLOSE), entry("a")))
    }

    @Test
    fun resemblanceFloorsAreOrdered() {
        assertTrue(Resemblance.CLOSE.floor > Resemblance.NORMAL.floor)
        assertTrue(Resemblance.NORMAL.floor > Resemblance.WIDE.floor)
        assertTrue(Resemblance.WIDE.floor > MoreResults.FLOOR)
    }

    @Test
    fun allFiltersCombineWithAnd() {
        val filters = Filters(
            date = DateFilter(DatePreset.LAST_24H), place = Place.INDOOR, text = TextFilter.WITH,
            folders = setOf("Camera"), types = setOf(FileSniffer.JPEG),
        )
        val good = entry("a", indoor = true, text = "x", folder = "Camera", mime = FileSniffer.JPEG, takenMs = hoursAgo(2))
        assertTrue(matches(filters, good))
        assertFalse(matches(filters, good.copy(indoor = false)))
        assertFalse(matches(filters, good.copy(text = "")))
        assertFalse(matches(filters, good.copy(folder = "Autre")))
        assertFalse(matches(filters, good.copy(mime = FileSniffer.PNG)))
        assertFalse(matches(filters, good.copy(takenMs = daysAgo(3))))
    }

    @Test
    fun dayPartContainsBoundaries() {
        assertTrue(DayPart.ALL.contains(0) && DayPart.ALL.contains(1439))
        assertTrue(DayPart.NIGHT.contains(1439) && DayPart.NIGHT.contains(0))
        assertFalse(DayPart.MORNING.contains(359))
    }

    @Test
    fun utcZoneIsUsedForTests() {
        assertEquals("UTC", UTC.id)
    }
}
