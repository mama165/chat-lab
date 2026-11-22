package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SourceListTest {
    @Test
    fun addingFoldersKeepsTheirOrder() {
        var list = emptyList<SourceFolder>()
        list = SourceList.add(list, "content://a", "Camera")
        list = SourceList.add(list, "content://b", "Screenshots")
        assertEquals(listOf("Camera", "Screenshots"), list.map { it.name })
    }

    @Test
    fun theSameFolderIsNotAddedTwice() {
        val list = SourceList.add(SourceList.add(emptyList(), "content://a", "Camera"), "content://a", "Camera")
        assertEquals(1, list.size)
    }

    @Test
    fun twoFoldersWithTheSameNameAreDistinguished() {
        var list = SourceList.add(emptyList(), "content://a", "Camera")
        list = SourceList.add(list, "content://b", "Camera")
        list = SourceList.add(list, "content://c", "Camera")
        assertEquals(listOf("Camera", "Camera (2)", "Camera (3)"), list.map { it.name })
    }

    @Test
    fun aBlankNameBecomesFolder() {
        assertEquals("Dossier", SourceList.add(emptyList(), "content://a", "  ").single().name)
    }

    @Test
    fun removingByAddress() {
        val list = SourceList.add(SourceList.add(emptyList(), "content://a", "Camera"), "content://b", "Screenshots")
        assertEquals(listOf("Screenshots"), SourceList.remove(list, "content://a").map { it.name })
        assertEquals(2, SourceList.remove(list, "content://zzz").size)
    }

    @Test
    fun aRemovedNameCanBeUsedAgain() {
        var list = SourceList.add(SourceList.add(emptyList(), "content://a", "Camera"), "content://b", "Camera")
        list = SourceList.remove(list, "content://b")
        list = SourceList.add(list, "content://c", "Camera")
        assertEquals(listOf("Camera", "Camera (2)"), list.map { it.name })
    }

    @Test
    fun encodeAndDecodeRoundTrip() {
        val list = listOf(SourceFolder("content://a/tree/x%3A1", "Camera"), SourceFolder("content://b", "Écrans, « vus »"))
        assertEquals(list, SourceList.decode(SourceList.encode(list)))
    }

    @Test
    fun decodeToleratesGarbage() {
        assertTrue(SourceList.decode(null).isEmpty())
        assertTrue(SourceList.decode("").isEmpty())
        assertTrue(SourceList.decode("pas du json").isEmpty())
        assertTrue(SourceList.decode("{}").isEmpty())
        assertEquals(1, SourceList.decode("""[1, {"uri": ""}, {"uri": "content://a", "name": "X"}]""").size)
    }

    @Test
    fun decodeDropsDuplicatesAndFillsMissingNames() {
        val list = SourceList.decode("""[{"uri":"content://a","name":"A"},{"uri":"content://a","name":"B"},{"uri":"content://c"}]""")
        assertEquals(listOf("A", "Dossier"), list.map { it.name })
    }

    @Test
    fun theOldSingleFolderBecomesAList() {
        assertEquals(listOf(SourceFolder("content://old", "Camera")), SourceList.fromLegacy("content://old", "Camera"))
        assertEquals(listOf(SourceFolder("content://old", "Dossier")), SourceList.fromLegacy("content://old", null))
        assertTrue(SourceList.fromLegacy(null, "x").isEmpty())
        assertTrue(SourceList.fromLegacy("", "x").isEmpty())
    }
}
