package com.example.chatlab

import com.example.chatlab.TestData.TIME
import com.example.chatlab.TestData.UTC
import com.example.chatlab.TestData.utc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DatesTest {
    @Test
    fun parseTakenReadsDayMonthYearWithOrWithoutTime() {
        assertEquals(SimpleDate(2026, 11, 18), Dates.parseTaken("18/11/2026 10:42"))
        assertEquals(SimpleDate(2025, 1, 5), Dates.parseTaken("05/01/2025"))
        assertEquals(SimpleDate(2025, 1, 5), Dates.parseTaken("5/1/2025"))
    }

    @Test
    fun parseTakenRejectsMissingOrImpossibleDates() {
        assertNull(Dates.parseTaken(""))
        assertNull(Dates.parseTaken("2026-11-18"))
        assertNull(Dates.parseTaken("18/13/2026"))
        assertNull(Dates.parseTaken("00/11/2026"))
        assertNull(Dates.parseTaken("30/02/2026"))
        assertNull(Dates.parseTaken("date inconnue"))
    }

    @Test
    fun parseTakenAcceptsTheLastDayOfAMonth() {
        assertEquals(SimpleDate(2024, 2, 29), Dates.parseTaken("29/02/2024"))
        assertNull(Dates.parseTaken("29/02/2026"))
        assertEquals(SimpleDate(2026, 4, 30), Dates.parseTaken("30/04/2026"))
        assertNull(Dates.parseTaken("31/04/2026"))
    }

    @Test
    fun parseTakenMillisKeepsTheTime() {
        assertEquals(utc(2026, 11, 18, 13, 42), Dates.parseTakenMillis("18/11/2026 13:42", UTC))
        assertEquals(utc(2026, 11, 18), Dates.parseTakenMillis("18/11/2026", UTC))
        assertNull(Dates.parseTakenMillis("18/11/2026 25:00", UTC))
        assertNull(Dates.parseTakenMillis("rien", UTC))
    }

    @Test
    fun exifDatesBecomeTheLibraryFormat() {
        assertEquals("18/11/2026 13:42", Dates.exifToTaken("2026:11:18 13:42:10"))
        assertEquals("05/01/2025 00:00", Dates.exifToTaken("2025:01:05"))
    }

    @Test
    fun badExifDatesAreRejected() {
        assertNull(Dates.exifToTaken(null))
        assertNull(Dates.exifToTaken(""))
        assertNull(Dates.exifToTaken("0000:00:00 00:00:00"))
        assertNull(Dates.exifToTaken("2026:02:30 10:00:00"))
        assertNull(Dates.exifToTaken("2026:11:18 24:10:00"))
        assertNull(Dates.exifToTaken("pas une date"))
        assertNull(Dates.exifToTaken("2026:11"))
    }

    @Test
    fun formatAndParseRoundTrip() {
        val millis = utc(2026, 3, 9, 7, 5)
        assertEquals("09/03/2026 07:05", Dates.formatTaken(millis, UTC))
        assertEquals(millis, Dates.parseTakenMillis(Dates.formatTaken(millis, UTC), UTC))
    }

    @Test
    fun minuteOfDay() {
        assertEquals(0, Dates.minuteOfDay(utc(2026, 1, 1, 0, 0), UTC))
        assertEquals(13 * 60 + 42, Dates.minuteOfDay(utc(2026, 1, 1, 13, 42), UTC))
        assertEquals(1439, Dates.minuteOfDay(utc(2026, 1, 1, 23, 59), UTC))
    }

    @Test
    fun longAndShortWriteMonthsInFrench() {
        assertEquals("18 nov. 2026", Dates.long("18/11/2026 10:42"))
        assertEquals("1 août 2025", Dates.long("01/08/2025"))
        assertEquals("date inconnue", Dates.long(""))
        assertEquals("21 nov.", Dates.short(SimpleDate(2026, 11, 21)))
        assertEquals("3 févr.", Dates.short(SimpleDate(2026, 2, 3)))
    }

    @Test
    fun displayIsRelativeForToday() {
        assertEquals("Aujourd'hui, 13:42", Dates.display(utc(2026, 11, 22, 13, 42), "", TIME))
        assertEquals("Aujourd'hui, 00:05", Dates.display(utc(2026, 11, 22, 0, 5), "", TIME))
    }

    @Test
    fun displayIsRelativeForYesterday() {
        assertEquals("Hier, 09:15", Dates.display(utc(2026, 11, 21, 9, 15), "", TIME))
    }

    @Test
    fun displayIsAbsoluteBeforeThat() {
        assertEquals("20 nov. 2026", Dates.display(utc(2026, 11, 20, 9, 15), "", TIME))
        assertEquals("5 mars 2025", Dates.display(utc(2025, 3, 5, 9, 15), "", TIME))
    }

    @Test
    fun displayFallsBackOnTheTextDateWhenTheInstantIsUnknown() {
        assertEquals("18 nov. 2026", Dates.display(0L, "18/11/2026 10:42", TIME))
        assertEquals("date inconnue", Dates.display(0L, "", TIME))
    }

    @Test
    fun filterLabelsAlwaysWriteTheYearOnBothDates() {
        val today = SimpleDate(2026, 11, 22)
        assertEquals("12 nov. 2026 – 17 nov. 2026", Dates.filterRangeLabel(SimpleDate(2026, 11, 12), SimpleDate(2026, 11, 17), today))
        assertEquals("12 oct. 2026 – 17 oct. 2026", Dates.filterRangeLabel(SimpleDate(2026, 10, 12), SimpleDate(2026, 10, 17), today))
        assertEquals("9 nov.", Dates.filterRangeLabel(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 9), today))
    }

    @Test
    fun rangeLabelsDependOnTheYearAndMonth() {
        val today = SimpleDate(2026, 11, 22)
        assertEquals("9 – 22 nov.", Dates.rangeLabel(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 22), today))
        assertEquals("28 oct. – 3 nov.", Dates.rangeLabel(SimpleDate(2026, 10, 28), SimpleDate(2026, 11, 3), today))
        assertEquals("9 nov.", Dates.rangeLabel(SimpleDate(2026, 11, 9), SimpleDate(2026, 11, 9), today))
        assertEquals("9 nov. 2025 – 22 nov. 2026", Dates.rangeLabel(SimpleDate(2025, 11, 9), SimpleDate(2026, 11, 22), today))
        assertEquals("9 nov. 2025 – 22 nov. 2025", Dates.rangeLabel(SimpleDate(2025, 11, 9), SimpleDate(2025, 11, 22), today))
        assertEquals("9 nov. 2025", Dates.rangeLabel(SimpleDate(2025, 11, 9), SimpleDate(2025, 11, 9), today))
    }

    @Test
    fun simpleDatesAreOrdered() {
        assertTrue(SimpleDate(2026, 11, 9) < SimpleDate(2026, 11, 22))
        assertTrue(SimpleDate(2026, 12, 1) > SimpleDate(2026, 11, 30))
        assertTrue(SimpleDate(2027, 1, 1) > SimpleDate(2026, 12, 31))
    }

    @Test
    fun plusDaysCrossesMonthsYearsAndLeapDays() {
        assertEquals(SimpleDate(2026, 12, 1), SimpleDate(2026, 11, 30).plusDays(1, UTC))
        assertEquals(SimpleDate(2027, 1, 1), SimpleDate(2026, 12, 31).plusDays(1, UTC))
        assertEquals(SimpleDate(2026, 11, 21), SimpleDate(2026, 11, 22).plusDays(-1, UTC))
        assertEquals(SimpleDate(2024, 2, 29), SimpleDate(2024, 2, 28).plusDays(1, UTC))
        assertEquals(SimpleDate(2026, 3, 1), SimpleDate(2026, 2, 28).plusDays(1, UTC))
    }

    @Test
    fun weekdaysStartOnMonday() {
        assertEquals(6, SimpleDate(2026, 11, 22).weekday(UTC)) // dimanche
        assertEquals(5, SimpleDate(2026, 11, 21).weekday(UTC)) // samedi
        assertEquals(0, SimpleDate(2026, 11, 16).weekday(UTC)) // lundi
        assertEquals(3, SimpleDate(2026, 11, 19).weekday(UTC)) // jeudi
    }

    @Test
    fun daysInMonth() {
        assertEquals(28, SimpleDate.daysIn(2026, 2))
        assertEquals(29, SimpleDate.daysIn(2024, 2))
        assertEquals(28, SimpleDate.daysIn(2100, 2))
        assertEquals(29, SimpleDate.daysIn(2000, 2))
        assertEquals(31, SimpleDate.daysIn(2026, 12))
        assertEquals(30, SimpleDate.daysIn(2026, 11))
    }

    @Test
    fun startAndEndOfADay() {
        val day = SimpleDate(2026, 11, 22)
        assertEquals(utc(2026, 11, 22), day.startMillis(UTC))
        assertEquals(utc(2026, 11, 23) - 1, day.endMillis(UTC))
    }

    @Test
    fun whenKnowsToday() {
        assertEquals(SimpleDate(2026, 11, 22), TIME.today)
    }
}
