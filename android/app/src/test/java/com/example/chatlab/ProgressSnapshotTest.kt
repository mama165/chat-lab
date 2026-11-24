package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressSnapshotTest {
    private val running = Progress(
        running = true,
        done = 2,
        total = 4,
        startedAt = 1_000L,
        levelLabel = "Normal",
        folders = listOf(FolderProgress("DCIM", 1, 2), FolderProgress("Pictures", 1, 2, later = true)),
    )

    @Test
    fun the_counts_and_the_percentage_are_written() {
        val json = running.snapshotJson(now = 5_000L)
        assertEquals(2, json.getInt("done"))
        assertEquals(4, json.getInt("total"))
        assertEquals(50, json.getInt("percent"))
        assertEquals("Normal", json.getString("levelLabel"))
        assertTrue(json.getBoolean("running"))
    }

    @Test
    fun each_folder_keeps_its_name_its_counts_and_whether_it_waits() {
        val folders = running.snapshotJson(now = 5_000L).getJSONArray("folders")
        val first = folders.getJSONObject(0)
        assertEquals("DCIM", first.getString("name"))
        assertEquals(1, first.getInt("done"))
        assertFalse(first.getBoolean("later"))
        assertTrue(folders.getJSONObject(1).getBoolean("later"))
    }

    @Test
    fun the_time_left_is_null_before_the_first_photo_is_done() {
        val early = running.copy(done = 0)
        val json = early.snapshotJson(now = 5_000L)
        assertTrue(json.isNull("remainingMs"))
        assertEquals(0, json.getInt("percent"))
    }

    @Test
    fun the_pauses_are_left_out_of_the_elapsed_time() {
        // Started at 1 s, paused at 5 s, read at 9 s: 4 s of work, not 8 s.
        val paused = running.copy(paused = true, pausedAt = 5_000L)
        assertEquals(4_000L, paused.snapshotJson(now = 9_000L).getLong("activeElapsedMs"))
    }

    @Test
    fun an_empty_analysis_has_no_division_by_zero() {
        val json = Progress(running = true, total = 0).snapshotJson(now = 1_000L)
        assertEquals(0, json.getInt("percent"))
        assertEquals(JSONObject.NULL, json.get("remainingMs"))
    }
}
