package com.example.chatlab

import com.example.chatlab.TestData.entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The place is chosen, but the search screen does not apply it yet. */
class SearchFilterPlaceTest {
    private val utc = java.util.TimeZone.getTimeZone("UTC")
    private val time = When(0L, utc)

    @Test
    fun theSearchLeavesThePlaceOut() {
        val filters = Filters(place = Place.INDOOR, kind = FileKind.PDF)
        assertEquals(Place.ALL, filters.withoutPlace().place)
        assertEquals(FileKind.PDF, filters.withoutPlace().kind)
    }

    @Test
    fun aPhotoOutsideTheChosenPlaceStillPassesOnceThePlaceIsLeftOut() {
        val outside = entry("out", indoor = false)
        assertFalse(Filters(place = Place.INDOOR).matches(outside, time))
        assertTrue(Filters(place = Place.INDOOR).withoutPlace().matches(outside, time))
    }

    @Test
    fun theChipStillShowsThePlace() {
        assertEquals("Lieu · Intérieur", Filters(place = Place.INDOOR).chips(time).single().label)
    }
}
