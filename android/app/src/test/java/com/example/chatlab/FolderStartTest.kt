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
