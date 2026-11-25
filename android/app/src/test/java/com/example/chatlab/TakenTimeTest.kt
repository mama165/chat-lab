package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

class TakenTimeTest {
    @Test
    fun theStoredValueWins() {
        assertEquals(1234L, TakenTime.millis(1234L, "2026-11-03 18:42"))
    }

    @Test
    fun withoutAStoredValueTheDateOfTheTextIsUsedLikeTheLibrary() {
        assertEquals(
            Dates.parseTakenMillis("2026-11-03 18:42", TimeZone.getDefault()),
            TakenTime.millis(0L, "2026-11-03 18:42"),
        )
    }

    @Test
    fun noDateAtAllGivesNull() {
        assertNull(TakenTime.millis(0L, ""))
    }
}
