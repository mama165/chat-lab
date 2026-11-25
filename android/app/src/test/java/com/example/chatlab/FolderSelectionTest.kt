package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderSelectionTest {
    private fun leaf(path: String) = FolderNode(path, path.substringAfterLast('/'), emptyList())
    private fun node(path: String, vararg children: FolderNode) = FolderNode(path, path.substringAfterLast('/'), children.toList())

    // DCIM/Camera, DCIM/Screenshots, Download/Factures
    private val dcim = node("/s/DCIM", leaf("/s/DCIM/Camera"), leaf("/s/DCIM/Screenshots"))
    private val download = node("/s/Download", leaf("/s/Download/Factures"))
    private val roots = listOf(dcim, download)
    // Every folder holds one photo: the tests below then see the whole tree.
    private val photos = FolderSelection.all(roots).associate { it.path to 1 }

    @Test
    fun aFolderWithItsSubfoldersIsAllFolders() {
        assertEquals(listOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots"), FolderSelection.withDescendants(dcim).map { it.path })
        assertEquals(5, FolderSelection.all(roots).size)
    }

    @Test
    fun togglingAFolderTicksItsSubfolders() {
        val selected = FolderSelection.toggle(dcim, emptySet(), locked = emptySet(), counts = photos)
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots"), selected)
    }

    @Test
    fun togglingATickedFolderUntickesItsSubfolders() {
        val selected = setOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots")
        assertEquals(emptySet<String>(), FolderSelection.toggle(dcim, selected, locked = emptySet(), counts = photos))
    }

    @Test
    fun aLockedFolderIsNeverTicked() {
        val locked = setOf("/s/DCIM/Screenshots")
        val selected = FolderSelection.toggle(dcim, emptySet(), locked, counts = photos)
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera"), selected)
        assertFalse("/s/DCIM/Screenshots" in selected)
    }

    @Test
    fun tickingOneSubfolderMakesItsParentPartial() {
        val selected = setOf("/s/DCIM/Camera")
        assertTrue(FolderSelection.isPartial(dcim, selected))
        assertFalse(FolderSelection.isPartial(download, selected))
        assertFalse(FolderSelection.isPartial(dcim, selected + "/s/DCIM"))
    }

    @Test
    fun tickingAFolderWithItsChildrenTicksOnlyTheTopOnes() {
        val selected = setOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots", "/s/Download/Factures")
        assertEquals(listOf("/s/DCIM", "/s/Download/Factures"), FolderSelection.tops(selected))
    }

    @Test
    fun aSubfolderAloneIsAddedAlone() {
        assertEquals(listOf("/s/DCIM/Camera"), FolderSelection.tops(setOf("/s/DCIM/Camera")))
    }

    @Test
    fun selectAllTicksEverythingButTheLockedFolders() {
        val selected = FolderSelection.selectAll(roots, locked = setOf("/s/DCIM/Screenshots"), counts = photos)
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera", "/s/Download", "/s/Download/Factures"), selected)
    }

    @Test
    fun selectAllLeavesOutFoldersWithoutPhotosAndNotYetCounted() {
        // DCIM/Camera has photos; DCIM itself holds them through Camera; Screenshots is empty; Download is not counted.
        val counts = mapOf("/s/DCIM" to 2, "/s/DCIM/Camera" to 2, "/s/DCIM/Screenshots" to 0)
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera"), FolderSelection.selectAll(roots, locked = emptySet(), counts = counts))
    }

    @Test
    fun togglingAFolderSkipsItsEmptySubfolders() {
        val counts = mapOf("/s/DCIM" to 1, "/s/DCIM/Camera" to 1, "/s/DCIM/Screenshots" to 0)
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera"), FolderSelection.toggle(dcim, emptySet(), locked = emptySet(), counts = counts))
    }

    @Test
    fun clearEmptiesTheSelection() {
        assertEquals(emptySet<String>(), FolderSelection.clear())
    }

    @Test
    fun theParentOfASubfolderIsItsFolder() {
        assertEquals("/s/DCIM", FolderSelection.parentOf("/s/DCIM/Camera"))
        assertEquals(emptyList<String>(), FolderSelection.tops(emptySet()))
    }
}
