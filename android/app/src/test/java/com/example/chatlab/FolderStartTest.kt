package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderStartTest {
    private fun start() = Progress(
        running = true,
        total = 5,
        folders = listOf(FolderProgress("Camera", 0, 3), FolderProgress("Download", 0, 2)),
    )

    @Test
    fun a_folder_is_running_as_soon_as_a_photo_of_it_is_taken() {
        val taken = start().taking(1, "a.jpg")
        assertEquals(FolderMark.RUNNING, taken.folders[1].mark())
        assertEquals(0, taken.folders[1].done)
        assertEquals("a.jpg", taken.folders[1].current)
        // The other folder is untouched.
        assertEquals(FolderMark.WAITING, taken.folders[0].mark())
    }

    @Test
    fun second_folder_starts_while_first_one_is_checked() {
        var progress = start()
        repeat(3) { progress = progress.taking(0, "p$it.jpg").advancing(0, "p$it.jpg") }
        assertEquals(FolderMark.FINISHED, progress.folders[0].mark())
        progress = progress.taking(1, "q.jpg")
        assertEquals(FolderMark.RUNNING, progress.folders[1].mark())
        assertEquals(0, progress.folders[1].done)
    }

    @Test
    fun taking_an_unknown_folder_changes_nothing() {
        val progress = start()
        assertEquals(progress, progress.taking(7, "x.jpg"))
    }
}

class GaugeFractionTest {
    @Test
    fun a_started_folder_with_no_photo_finished_turns() {
        val folder = FolderProgress("Pictures", 0, 6, current = "a.jpg")
        assertEquals(null, folder.gaugeFraction())
    }

    @Test
    fun a_running_folder_follows_its_real_count() {
        assertEquals(0.5f, FolderProgress("Pictures", 3, 6).gaugeFraction()!!, 1e-6f)
    }

    @Test
    fun waiting_and_finished_folders_have_no_running_gauge() {
        assertEquals(null, FolderProgress("Waiting", 0, 6).gaugeFraction())
        // A finished folder shows a check, not a gauge.
        assertEquals(null, FolderProgress("Done", 6, 6).gaugeFraction())
    }
}
