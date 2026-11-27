package com.example.chatlab

import com.example.chatlab.TestData.TIME
import com.example.chatlab.TestData.UTC
import com.example.chatlab.TestData.entry
import com.example.chatlab.TestData.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilterOptionsTest {
    private val entries = listOf(
        entry("a", folder = "Camera", mime = FileSniffer.JPEG, takenMs = utc(2026, 11, 9, 10)),
        entry("b", folder = "Camera", mime = FileSniffer.JPEG, takenMs = utc(2026, 11, 9, 18)),
        entry("c", folder = "Screenshots", mime = FileSniffer.PNG, takenMs = utc(2026, 11, 12, 8)),
        entry("d", folder = "", mime = "", takenMs = 0L),
        entry("e", folder = "WhatsApp Images", mime = FileSniffer.WEBP, takenMs = utc(2026, 10, 3)),
    )

    @Test
    fun foldersAreTheOnesFoundInTheLibraryWithTheirCounts() {
        assertEquals(
            listOf(FolderOption("Camera", 2), FolderOption("Screenshots", 1), FolderOption("WhatsApp Images", 1)),
            FilterOptions.folders(entries),
        )
    }

    @Test
    fun photosWithoutFolderAreNotListedAsAFolder() {
        assertTrue(FilterOptions.folders(listOf(entry("a"), entry("b"))).isEmpty())
    }

    @Test
    fun typesAreOnlyThoseFoundAndSupported() {
        assertEquals(
            listOf(TypeOption(FileSniffer.JPEG, 2), TypeOption(FileSniffer.PNG, 1), TypeOption(FileSniffer.WEBP, 1)),
            FilterOptions.types(entries),
        )
    }

    @Test
    fun unsupportedTypesAreNeverProposed() {
        val list = listOf(
            entry("a", mime = FileSniffer.PDF),
            entry("b", mime = FileSniffer.GIF),
            entry("c", mime = FileSniffer.HEIC),
            entry("d", mime = FileSniffer.JPEG),
        )
        assertEquals(listOf(TypeOption(FileSniffer.JPEG, 1)), FilterOptions.types(list))
    }

    @Test
    fun noTypeIsProposedWhenNoneIsFound() {
        assertTrue(FilterOptions.types(emptyList()).isEmpty())
        assertTrue(FilterOptions.types(listOf(entry("a"))).isEmpty())
    }

    @Test
    fun photoDaysIgnoreUndatedPhotos() {
        assertEquals(
            setOf(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 12), SimpleDate(2026, 10, 3)),
            FilterOptions.photoDays(entries, UTC),
        )
    }

    @Test
    fun november2026StartsOnASunday() {
        val weeks = CalendarGrid.month(2026, 11, emptySet(), UTC)
        assertEquals(listOf(null, null, null, null, null, null, SimpleDate(2026, 11, 1)), weeks[0].map { it.day })
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(30, weeks.flatten().count { it.day != null })
    }

    @Test
    fun october2026StartsOnAThursday() {
        val weeks = CalendarGrid.month(2026, 10, emptySet(), UTC)
        assertEquals(3, weeks[0].takeWhile { it.day == null }.size)
        assertEquals(SimpleDate(2026, 10, 1), weeks[0][3].day)
        assertEquals(31, weeks.flatten().count { it.day != null })
    }

    @Test
    fun theLastWeekIsPaddedToSevenCells() {
        val weeks = CalendarGrid.month(2026, 2, emptySet(), UTC)
        assertTrue(weeks.all { it.size == 7 })
        assertEquals(28, weeks.flatten().count { it.day != null })
    }

    @Test
    fun leapFebruaryHasTwentyNineDays() {
        assertEquals(29, CalendarGrid.month(2024, 2, emptySet(), UTC).flatten().count { it.day != null })
    }

    @Test
    fun daysWithPhotosCarryADot() {
        val weeks = CalendarGrid.month(2026, 11, setOf(SimpleDate(2026, 11, 9)), UTC).flatten()
        assertTrue(weeks.first { it.day == SimpleDate(2026, 11, 9) }.hasPhotos)
        assertFalse(weeks.first { it.day == SimpleDate(2026, 11, 10) }.hasPhotos)
    }

    @Test
    fun monthNavigationCrossesYears() {
        assertEquals(2026 to 10, CalendarGrid.previous(2026, 11))
        assertEquals(2025 to 12, CalendarGrid.previous(2026, 1))
        assertEquals(2026 to 12, CalendarGrid.next(2026, 11))
        assertEquals(2027 to 1, CalendarGrid.next(2026, 12))
        assertEquals("Novembre 2026", CalendarGrid.title(2026, 11))
    }

    @Test
    fun theWeekStartsOnMonday() {
        assertEquals(listOf("L", "M", "M", "J", "V", "S", "D"), CalendarGrid.WEEKDAYS)
    }

    @Test
    fun aSecondTapOnTheOnlyDayChosenCancelsIt() {
        val day = SimpleDate(2026, 11, 12)
        val chosen = DateSelection().tap(day)
        assertEquals(DateSelection(day, null), chosen)
        assertEquals(DateSelection(), chosen.tap(day))
        assertNull(DateSelection().tap(day).tap(day).toRange())
    }

    @Test
    fun theFirstTapSetsTheStartAndTheSecondTheEnd() {
        val first = DateSelection().tap(SimpleDate(2026, 11, 9))
        assertEquals(DateSelection(SimpleDate(2026, 11, 9), null), first)
        val second = first.tap(SimpleDate(2026, 11, 22))
        assertEquals(DateSelection(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22)), second)
    }

    @Test
    fun tappingBeforeTheStartSwapsTheDates() {
        val selection = DateSelection(SimpleDate(2026, 11, 22), null).tap(SimpleDate(2026, 11, 9))
        assertEquals(DateSelection(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22)), selection)
    }

    @Test
    fun aThirdTapStartsOver() {
        val done = DateSelection(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22))
        assertEquals(DateSelection(SimpleDate(2026, 11, 15), null), done.tap(SimpleDate(2026, 11, 15)))
    }

    @Test
    fun tappingTheSameDayTwiceCancelsIt() {
        val selection = DateSelection().tap(SimpleDate(2026, 11, 9)).tap(SimpleDate(2026, 11, 9))
        assertEquals(DateSelection(), selection)
        assertNull(selection.toRange())
    }

    @Test
    fun theSelectionKnowsWhichDaysItCovers() {
        val selection = DateSelection(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 12))
        assertTrue(selection.contains(SimpleDate(2026, 11, 9)))
        assertTrue(selection.contains(SimpleDate(2026, 11, 10)))
        assertTrue(selection.contains(SimpleDate(2026, 11, 12)))
        assertFalse(selection.contains(SimpleDate(2026, 11, 13)))
        assertFalse(selection.contains(SimpleDate(2026, 11, 8)))
        assertTrue(DateSelection(SimpleDate(2026, 11, 9), null).contains(SimpleDate(2026, 11, 9)))
        assertFalse(DateSelection().contains(SimpleDate(2026, 11, 9)))
    }

    @Test
    fun noRangeUntilADayIsChosen() {
        assertNull(DateSelection().toRange())
        assertEquals(
            DateRange(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22), DayPart.EVENING),
            DateSelection(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22)).toRange(DayPart.EVENING),
        )
        assertEquals(DateSelection(), DateSelection.of(null))
    }

    @Test
    fun shortcutsOnASunday() {
        // TIME is Sunday 22 November 2026.
        assertEquals(DateRange(SimpleDate(2026, 11, 22), SimpleDate(2026, 11, 22)), DateShortcut.TODAY.range(TIME))
        assertEquals(DateRange(SimpleDate(2026, 11, 21), SimpleDate(2026, 11, 21)), DateShortcut.YESTERDAY.range(TIME))
        assertEquals(DateRange(SimpleDate(2026, 11, 21), SimpleDate(2026, 11, 22)), DateShortcut.WEEKEND.range(TIME))
        assertEquals(DateRange(SimpleDate(2026, 11, 1), SimpleDate(2026, 11, 22)), DateShortcut.THIS_MONTH.range(TIME))
        assertEquals(DateRange(SimpleDate(2026, 10, 1), SimpleDate(2026, 10, 31)), DateShortcut.LAST_MONTH.range(TIME))
    }

    @Test
    fun theWeekendOnAWednesdayIsTheLastOne() {
        val wednesday = When(utc(2026, 11, 18, 10), UTC)
        assertEquals(DateRange(SimpleDate(2026, 11, 14), SimpleDate(2026, 11, 15)), DateShortcut.WEEKEND.range(wednesday))
    }

    @Test
    fun theWeekendOnAMondayAndAFriday() {
        assertEquals(
            DateRange(SimpleDate(2026, 11, 14), SimpleDate(2026, 11, 15)),
            DateShortcut.WEEKEND.range(When(utc(2026, 11, 16, 10), UTC)),
        )
        assertEquals(
            DateRange(SimpleDate(2026, 11, 14), SimpleDate(2026, 11, 15)),
            DateShortcut.WEEKEND.range(When(utc(2026, 11, 20, 10), UTC)),
        )
    }

    @Test
    fun theWeekendOnASaturdayStopsToday() {
        val saturday = When(utc(2026, 11, 21, 10), UTC)
        assertEquals(DateRange(SimpleDate(2026, 11, 21), SimpleDate(2026, 11, 21)), DateShortcut.WEEKEND.range(saturday))
    }

    @Test
    fun lastMonthInJanuaryIsDecember() {
        val january = When(utc(2027, 1, 10), UTC)
        assertEquals(DateRange(SimpleDate(2026, 12, 1), SimpleDate(2026, 12, 31)), DateShortcut.LAST_MONTH.range(january))
    }

    @Test
    fun yesterdayCrossesTheMonth() {
        val first = When(utc(2026, 12, 1, 10), UTC)
        assertEquals(DateRange(SimpleDate(2026, 11, 30), SimpleDate(2026, 11, 30)), DateShortcut.YESTERDAY.range(first))
    }
}
