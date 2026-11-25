package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AddFolderTreeTest {
    private val root = "/s"
    // Pictures has a Screenshots subfolder; DCIM has a Camera subfolder; Empty has only empty folders.
    private val shots = FolderNode("/s/Pictures/Screenshots", "Screenshots", emptyList())
    private val pictures = FolderNode("/s/Pictures", "Pictures", listOf(shots))
    private val camera = FolderNode("/s/DCIM/Camera", "Camera", emptyList())
    private val dcim = FolderNode("/s/DCIM", "DCIM", listOf(camera))
    private val emptyLeaf = FolderNode("/s/Empty/Deep", "Deep", emptyList())
    private val empty = FolderNode("/s/Empty", "Empty", listOf(emptyLeaf))
    private val tree = listOf(dcim, empty, pictures)

    @Test
    fun a_folder_without_pending_photos_is_removed_with_its_empty_branch() {
        val visible = AddFolderTree.visible(tree, pending = mapOf("/s/DCIM/Camera" to 3, "/s/DCIM" to 3), added = emptySet())
        assertEquals(listOf("/s/DCIM"), visible.map { it.path })
        assertEquals(listOf("/s/DCIM/Camera"), visible.single().children.map { it.path })
    }

    @Test
    fun a_parent_stays_when_a_photo_is_pending_deep_below() {
        val visible = AddFolderTree.visible(listOf(pictures), pending = mapOf("/s/Pictures" to 1, "/s/Pictures/Screenshots" to 1), added = emptySet())
        assertEquals("/s/Pictures", visible.single().path)
        assertEquals("/s/Pictures/Screenshots", visible.single().children.single().path)
    }

    @Test
    fun a_parent_with_only_known_photos_below_is_removed() {
        // Its only subfolder is already analysed: nothing is pending under the parent.
        val visible = AddFolderTree.visible(listOf(pictures), pending = emptyMap(), added = emptySet())
        assertTrue(visible.isEmpty())
    }

    @Test
    fun an_added_folder_and_everything_under_it_is_not_offered() {
        val visible = AddFolderTree.visible(
            tree,
            pending = mapOf("/s/DCIM" to 3, "/s/DCIM/Camera" to 3, "/s/Pictures" to 1, "/s/Pictures/Screenshots" to 1),
            added = setOf("/s/DCIM"),
        )
        assertEquals(listOf("/s/Pictures"), visible.map { it.path })
    }

    @Test
    fun pending_counts_leave_out_the_photos_under_an_added_folder() {
        val counts = AddFolderTree.pendingCounts(
            root,
            pendingPhotos = listOf("/s/DCIM/Camera/a.jpg", "/s/Pictures/Screenshots/b.png"),
            added = setOf("/s/DCIM"),
        )
        assertEquals(null, counts["/s/DCIM"])
        assertEquals(1, counts["/s/Pictures"])
        assertEquals(1, counts["/s/Pictures/Screenshots"])
    }

    @Test
    fun covered_is_true_for_the_folder_itself_and_its_subfolders_only() {
        assertTrue(AddFolderTree.covered("/s/DCIM", setOf("/s/DCIM")))
        assertTrue(AddFolderTree.covered("/s/DCIM/Camera", setOf("/s/DCIM")))
        assertTrue(!AddFolderTree.covered("/s/DCIMX", setOf("/s/DCIM")))
    }

    @Test
    fun search_ignores_case_and_accents_and_keeps_the_match_position() {
        val hits = AddFolderTree.hits(tree, root, "SCREEN")
        assertEquals(listOf("/s/Pictures/Screenshots"), hits.map { it.path })
        assertEquals(0, hits.single().start)
        assertEquals(6, hits.single().end)
        assertEquals("Pictures › Screenshots", hits.single().label)
    }

    @Test
    fun a_match_inside_the_name_is_found_after_the_prefix_matches() {
        val folders = listOf(
            FolderNode("/s/Sons", "Sons", emptyList()),
            FolderNode("/s/Enregistrements", "Enregistrements", emptyList()),
        )
        assertEquals(listOf("/s/Sons"), AddFolderTree.hits(folders, root, "son").map { it.path })
        assertEquals(listOf("/s/Enregistrements"), AddFolderTree.hits(folders, root, "istr").map { it.path })
    }

    @Test
    fun an_accent_is_not_needed_to_find_a_folder() {
        val folders = listOf(FolderNode("/s/Téléchargements", "Téléchargements", emptyList()))
        assertEquals(1, AddFolderTree.hits(folders, root, "telechargements").size)
        assertEquals(0, AddFolderTree.hits(folders, root, "").size)
    }

    @Test
    fun suggestion_is_the_rest_of_the_first_folder_starting_with_the_query() {
        val hits = AddFolderTree.hits(tree, root, "screen")
        assertEquals("shots", AddFolderTree.suggestion(hits, "screen"))
        assertEquals("", AddFolderTree.suggestion(hits, "screenshots"))
        assertEquals("", AddFolderTree.suggestion(emptyList(), "screen"))
    }
}
