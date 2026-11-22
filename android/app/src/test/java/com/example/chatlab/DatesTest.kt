package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class DatesTest {
    @Test
    fun parseTaken_readsDayMonthYearWithOrWithoutTime() {
        assertEquals(SimpleDate(2026, 11, 18), Dates.parseTaken("18/11/2026 10:42"))
        assertEquals(SimpleDate(2025, 1, 5), Dates.parseTaken("05/01/2025"))
        assertEquals(SimpleDate(2025, 1, 5), Dates.parseTaken("5/1/2025"))
    }

    @Test
    fun parseTaken_rejectsMissingOrBadDates() {
        assertNull(Dates.parseTaken(""))
        assertNull(Dates.parseTaken("2026-11-18"))
        assertNull(Dates.parseTaken("18/13/2026"))
        assertNull(Dates.parseTaken("00/11/2026"))
        assertNull(Dates.parseTaken("date inconnue"))
    }

    @Test
    fun long_writesTheMonthInFrench() {
        assertEquals("18 nov. 2026", Dates.long("18/11/2026 10:42"))
        assertEquals("1 août 2025", Dates.long("01/08/2025"))
        assertEquals("date inconnue", Dates.long(""))
    }

    @Test
    fun short_hasNoYear() {
        assertEquals("21 nov.", Dates.short(SimpleDate(2026, 11, 21)))
        assertEquals("3 févr.", Dates.short(SimpleDate(2026, 2, 3)))
    }

    @Test
    fun of_readsTheCalendarDate() {
        val calendar = Calendar.getInstance().apply { clear(); set(2026, Calendar.MARCH, 9, 12, 0) }
        assertEquals(SimpleDate(2026, 3, 9), SimpleDate.of(calendar.timeInMillis))
    }
}
