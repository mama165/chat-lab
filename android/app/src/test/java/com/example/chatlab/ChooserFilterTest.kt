package com.example.chatlab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChooserFilterTest {
    private val counts = mapOf("/s/Camera" to 120, "/s/Alarms" to 0)

    @Test
    fun a_folder_with_photos_is_shown() {
        assertTrue(ChooserFilter.visible("/s/Camera", counts, emptySet()))
    }

    @Test
    fun a_folder_counted_at_zero_is_hidden() {
        assertFalse(ChooserFilter.visible("/s/Alarms", counts, emptySet()))
    }

    @Test
    fun a_folder_not_counted_yet_stays_visible() {
        assertTrue(ChooserFilter.visible("/s/Documents", counts, emptySet()))
    }

    @Test
    fun an_added_folder_stays_visible_even_with_no_photos() {
        assertTrue(ChooserFilter.visible("/s/Alarms", counts, setOf("/s/Alarms")))
    }
}
