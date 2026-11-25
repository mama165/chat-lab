package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTextTest {
    @Test
    fun durations_under_an_hour_are_in_minutes() {
        assertEquals("38 min", HistoryText.shortDuration(38))
        assertEquals("0 min", HistoryText.shortDuration(0))
    }

    @Test
    fun durations_from_an_hour_are_in_hours_and_two_digits_of_minutes() {
        assertEquals("1 h 02", HistoryText.shortDuration(62))
        assertEquals("2 h 15", HistoryText.shortDuration(135))
    }

    @Test
    fun speed_is_whole_when_whole_and_one_decimal_otherwise() {
        assertEquals("2 photos /s", HistoryText.photosPerSecond(2.0))
        assertEquals("3,6 photos /s", HistoryText.photosPerSecond(3.6))
    }

    @Test
    fun heating_names_follow_the_android_levels() {
        assertEquals("normale", HistoryText.heating(0))
        assertEquals("modérée", HistoryText.heating(2))
        assertEquals("élevée", HistoryText.heating(3))
        assertEquals("d'urgence", HistoryText.heating(5))
    }

    @Test
    fun photos_in_folders_agree_in_number() {
        assertEquals("1247 dans 9 dossiers", HistoryText.photosInFolders(1247, 9))
        assertEquals("12 dans 1 dossier", HistoryText.photosInFolders(12, 1))
    }

    @Test
    fun speed_is_zero_without_time() {
        assertEquals(0.0, HistoryText.speed(10, 0.0), 1e-9)
        assertEquals(2.0, HistoryText.speed(120, 60.0), 1e-9)
    }
}
