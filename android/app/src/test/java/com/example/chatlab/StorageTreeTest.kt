package com.example.chatlab

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class StorageTreeTest {
    private lateinit var root: File

    @Before
    fun setUp() {
        root = Files.createTempDirectory("storage").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun dir(path: String) = File(root, path).also { it.mkdirs() }

    @Test
    fun foldersAreListedWithTheirSubfoldersInAlphabeticalOrder() {
        dir("DCIM/Camera")
        dir("Download")
        dir("Pictures/Screenshots")
        val tree = StorageTree.build(root)
        assertEquals(listOf("DCIM", "Download", "Pictures"), tree.map { it.name })
        assertEquals(listOf("Camera"), tree.first { it.name == "DCIM" }.children.map { it.name })
    }

    @Test
    fun filesAreNeverListed() {
        dir("Download")
        File(root, "Download/photo.jpg").writeBytes(byteArrayOf(1, 2, 3))
        File(root, "notes.txt").writeText("bonjour")
        val tree = StorageTree.build(root)
        assertTrue(tree.single().children.isEmpty())
        assertEquals(listOf("Download"), tree.map { it.name })
    }

    @Test
    fun hiddenFoldersAreSkipped() {
        dir(".thumbnails")
        dir("Pictures")
        assertEquals(listOf("Pictures"), StorageTree.build(root).map { it.name })
    }

    @Test
    fun androidDataAndObbAreSkippedButMediaIsKept() {
        dir("Android/data/com.app")
        dir("Android/obb")
        dir("Android/media/com.whatsapp/WhatsApp/Media")
        val android = StorageTree.build(root).single { it.name == "Android" }
        assertEquals(listOf("media"), android.children.map { it.name })
    }

    @Test
    fun aMissingRootGivesAnEmptyTree() {
        assertTrue(StorageTree.build(File(root, "absent")).isEmpty())
    }

    @Test
    fun theDepthIsLimited() {
        dir("a/b/c/d")
        val tree = StorageTree.build(root, maxDepth = 2)
        assertEquals(listOf("a"), tree.map { it.name })
        assertEquals(listOf("b"), tree.single().children.map { it.name })
        assertTrue(tree.single().children.single().children.isEmpty())
    }
}
