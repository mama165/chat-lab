package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class FileTallyTest {
    private lateinit var root: File

    @org.junit.Before
    fun setUp() {
        root = Files.createTempDirectory("tally").toFile()
    }

    @org.junit.After
    fun tearDown() {
        root.deleteRecursively()
    }

    private fun file(path: String) = File(root, path).also { it.parentFile.mkdirs(); it.writeBytes(byteArrayOf(1)) }

    @Test
    fun anEmptyFolderHasNoFile() {
        assertEquals(0, FileTally.count(JavaDirNode(root)))
    }

    @Test
    fun everyFileIsCountedIncludingInSubfolders() {
        file("a.jpg")
        file("sous/b.png")
        file("sous/profond/c.webp")
        file("sous/profond/encore/d.txt")
        assertEquals(4, FileTally.count(JavaDirNode(root)))
    }

    @Test
    fun aFolderNotReadableCountsZero() {
        val refused = object : DirNode {
            override val name = "refusé"
            override fun children(): List<Any>? = null
        }
        assertEquals(0, FileTally.count(refused))
    }

    @Test
    fun theFractionFollowsTheRealProgress() {
        assertEquals(0f, Progress01.of(0, 0), 0.0001f)
        assertEquals(0f, Progress01.of(0, 10), 0.0001f)
        assertEquals(0.5f, Progress01.of(5, 10), 0.0001f)
        assertEquals(1f, Progress01.of(10, 10), 0.0001f)
    }

    @Test
    fun theFractionNeverGoesBeyondOneNorBelowZero() {
        assertEquals(1f, Progress01.of(12, 10), 0.0001f)
        assertEquals(0f, Progress01.of(-3, 10), 0.0001f)
        assertTrue(Progress01.of(7, 3) <= 1f)
    }
}
