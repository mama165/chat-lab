package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderCountsTest {
    private val root = "/storage/emulated/0"

    @Test
    fun aPhotoCountsForEveryFolderAboveIt() {
        val counts = FolderCounts.countByFolder(listOf("$root/DCIM/Camera/a.jpg"), root)
        assertEquals(1, counts["$root/DCIM/Camera"])
        assertEquals(1, counts["$root/DCIM"])
        assertEquals(1, counts[root])
    }

    @Test
    fun siblingsAddUpInTheirParent() {
        val counts = FolderCounts.countByFolder(
            listOf("$root/DCIM/Camera/a.jpg", "$root/DCIM/Camera/b.jpg", "$root/DCIM/Screenshots/c.png"),
            root,
        )
        assertEquals(2, counts["$root/DCIM/Camera"])
        assertEquals(1, counts["$root/DCIM/Screenshots"])
        assertEquals(3, counts["$root/DCIM"])
    }

    @Test
    fun aFolderWithoutPhotosIsNotInTheResult() {
        val counts = FolderCounts.countByFolder(listOf("$root/DCIM/a.jpg"), root)
        assertEquals(null, counts["$root/Download"])
    }

    @Test
    fun noPhotosGivesNoCounts() {
        assertEquals(emptyMap<String, Int>(), FolderCounts.countByFolder(emptyList(), root))
    }
}
