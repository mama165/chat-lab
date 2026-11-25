package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstRunListTest {
    private fun leaf(path: String) = FolderNode(path, path.substringAfterLast('/'), emptyList())
    private fun node(path: String, vararg children: FolderNode) = FolderNode(path, path.substringAfterLast('/'), children.toList())

    // DCIM (no photo of its own) holds Camera; Pictures holds Screenshots and itself has one photo.
    private val camera = leaf("/s/DCIM/Camera")
    private val dcim = node("/s/DCIM", camera)
    private val shots = leaf("/s/Pictures/Screenshots")
    private val pictures = node("/s/Pictures", shots)
    private val roots = listOf(dcim, pictures)

    @Test
    fun a_photo_counts_for_its_own_folder_only() {
        val direct = FolderCounts.directCounts(listOf("/s/DCIM/Camera/a.jpg", "/s/DCIM/Camera/b.jpg", "/s/Pictures/c.png"))
        assertEquals(mapOf("/s/DCIM/Camera" to 2, "/s/Pictures" to 1), direct)
    }

    @Test
    fun the_list_keeps_only_the_folders_that_hold_photos_of_their_own() {
        val direct = mapOf("/s/DCIM/Camera" to 2, "/s/Pictures" to 1)
        // DCIM holds no photo of its own, so it is not listed; Camera and Pictures are.
        assertEquals(
            listOf("/s/DCIM/Camera", "/s/Pictures"),
            FolderCounts.withPhotosOfTheirOwn(roots, direct).map { it.path },
        )
    }

    @Test
    fun ticking_one_folder_does_not_tick_its_subfolders() {
        val selected = FolderSelection.tickOne("/s/DCIM", emptySet(), locked = emptySet())
        assertEquals(setOf("/s/DCIM"), selected)
        assertEquals(emptySet<String>(), FolderSelection.tickOne("/s/DCIM", selected, locked = emptySet()))
    }

    @Test
    fun a_locked_folder_cannot_be_ticked_or_unticked() {
        assertEquals(emptySet<String>(), FolderSelection.tickOne("/s/DCIM", emptySet(), locked = setOf("/s/DCIM")))
    }

    @Test
    fun a_parent_and_a_child_both_ticked_add_only_the_parent() {
        // Camera is under DCIM: the analysis of DCIM already covers it.
        val selected = setOf("/s/DCIM", "/s/DCIM/Camera")
        assertEquals(listOf("/s/DCIM"), FolderSelection.tops(selected))
    }
}
