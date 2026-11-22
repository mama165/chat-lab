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

    @Test
    fun aFolderWithItsSubfoldersIsAllFolders() {
        assertEquals(listOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots"), FolderSelection.withDescendants(dcim).map { it.path })
        assertEquals(5, FolderSelection.all(roots).size)
    }

    @Test
    fun togglingAFolderTicksItsSubfolders() {
        val selected = FolderSelection.toggle(dcim, emptySet(), locked = emptySet())
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots"), selected)
    }

    @Test
    fun togglingATickedFolderUntickesItsSubfolders() {
        val selected = setOf("/s/DCIM", "/s/DCIM/Camera", "/s/DCIM/Screenshots")
        assertEquals(emptySet<String>(), FolderSelection.toggle(dcim, selected, locked = emptySet()))
    }

    @Test
    fun aLockedFolderIsNeverTicked() {
        val locked = setOf("/s/DCIM/Screenshots")
        val selected = FolderSelection.toggle(dcim, emptySet(), locked)
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
        val selected = FolderSelection.selectAll(roots, locked = setOf("/s/DCIM/Screenshots"))
        assertEquals(setOf("/s/DCIM", "/s/DCIM/Camera", "/s/Download", "/s/Download/Factures"), selected)
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
