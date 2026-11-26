package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TypedDateTest {
    private val paris: TimeZone = TimeZone.getTimeZone("Europe/Paris")

    /** Thursday 26 November 2026, 10:00 in Paris. */
    private val time = When(
        Calendar.getInstance(paris).apply { clear(); set(2026, Calendar.NOVEMBER, 26, 10, 0) }.timeInMillis,
        paris,
    )

    private fun day(year: Int, month: Int, day: Int) = SimpleDate(year, month, day)

    private fun parse(text: String) = TypedDates.parse(text, time)

    @Test
    fun relativeDaysAreRead() {
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26)), parse("aujourd'hui").range)
        assertEquals(DateRange(day(2026, 11, 25), day(2026, 11, 25)), parse("hier").range)
        assertEquals(DateRange(day(2026, 11, 24), day(2026, 11, 24)), parse("avant-hier").range)
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26)), parse("aujourdhui").range)
    }

    @Test
    fun relativeDaysAreCaseAndAccentInsensitive() {
        assertEquals(day(2026, 11, 25), parse("HIER").range?.from)
        assertEquals(day(2026, 11, 26), parse("Aujourd’hui").range?.from)
    }

    @Test
    fun timeOfDayWithTodayOrAYesterday() {
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26), DayPart.MORNING), parse("ce matin").range)
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26), DayPart.AFTERNOON), parse("cet après-midi").range)
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26), DayPart.EVENING), parse("ce soir").range)
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26), DayPart.NIGHT), parse("cette nuit").range)
        assertEquals(DateRange(day(2026, 11, 25), day(2026, 11, 25), DayPart.EVENING), parse("hier soir").range)
    }

    @Test
    fun dayMonthAndYearAreRead() {
        val result = parse("26 novembre 2026")
        assertEquals(DateRange(day(2026, 11, 26), day(2026, 11, 26)), result.range)
        assertEquals("", result.rest)
    }

    @Test
    fun dayAndMonthWithoutYearUseThisYearWhenItIsNotToCome() {
        assertEquals(day(2026, 3, 3), parse("3 mars").range?.from)
        assertEquals(day(2026, 11, 26), parse("26 nov.").range?.from)
    }

    @Test
    fun dayAndMonthStillToComeUseLastYear() {
        assertEquals(day(2025, 12, 25), parse("25 décembre").range?.from)
    }

    @Test
    fun numericDatesAreRead() {
        assertEquals(day(2026, 11, 26), parse("26/11/2026").range?.from)
        assertEquals(day(2026, 11, 20), parse("20/11").range?.from)
        assertEquals(day(2026, 11, 20), parse("2026-11-20").range?.from)
    }

    @Test
    fun monthAndYearGiveTheWholeMonth() {
        val range = parse("novembre 2026").range
        assertEquals(day(2026, 11, 1), range?.from)
        assertEquals(day(2026, 11, 30), range?.to)
    }

    @Test
    fun wordsAroundTheDateStayAsTheSearch() {
        val result = parse("plage hier")
        assertEquals(day(2026, 11, 25), result.range?.from)
        assertEquals("plage", result.rest)
    }

    @Test
    fun dateInTheMiddleOfTheWordsIsTakenOut() {
        val result = parse("chien 26/11/2026 parc")
        assertEquals(day(2026, 11, 26), result.range?.from)
        assertEquals("chien parc", result.rest)
    }

    @Test
    fun textWithoutDateIsKeptAsItIs() {
        val result = parse("Fête des rosés")
        assertNull(result.range)
        assertEquals("Fête des rosés", result.rest)
    }

    @Test
    fun impossibleDateIsNotTaken() {
        val result = parse("31 février")
        assertNull(result.range)
        assertEquals("31 février", result.rest)
    }

    @Test
    fun wordsThatLookLikeMonthsAloneAreNotDates() {
        val result = parse("mars rouge")
        assertNull(result.range)
        assertEquals("mars rouge", result.rest)
    }

    @Test
    fun emptyTextHasNoDateAndNoWords() {
        val result = parse("   ")
        assertNull(result.range)
        assertEquals("", result.rest)
    }
}
