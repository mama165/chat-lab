package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RepriseTimelineTest {
    @Test
    fun the_steps_come_in_order() {
        assertTrue(RepriseTimeline.FOLDING < RepriseTimeline.CALC)
        assertTrue(RepriseTimeline.CALC < RepriseTimeline.REMAINING)
        assertTrue(RepriseTimeline.REMAINING < RepriseTimeline.FOLDERS)
    }

    @Test
    fun no_folder_comes_back_before_the_folders_step() {
        assertEquals(0, RepriseTimeline.foldersShownAt(0L, folders = 4))
        assertEquals(0, RepriseTimeline.foldersShownAt(RepriseTimeline.FOLDERS - 1, folders = 4))
    }

    @Test
    fun the_folders_come_back_one_by_one() {
        assertEquals(1, RepriseTimeline.foldersShownAt(RepriseTimeline.FOLDERS, folders = 4))
        assertEquals(2, RepriseTimeline.foldersShownAt(RepriseTimeline.FOLDERS + RepriseTimeline.FOLDER_STEP, folders = 4))
        assertEquals(4, RepriseTimeline.foldersShownAt(RepriseTimeline.FOLDERS + 3 * RepriseTimeline.FOLDER_STEP, folders = 4))
    }

    @Test
    fun never_more_folders_than_there_are() {
        assertEquals(4, RepriseTimeline.foldersShownAt(60_000L, folders = 4))
        assertEquals(0, RepriseTimeline.foldersShownAt(60_000L, folders = 0))
    }
}
