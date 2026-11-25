package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FolderZeroFillTest {
    // A contains B, B contains C. Only A has a photo, in A itself.
    private val c = FolderNode("/s/A/B/C", "C", emptyList())
    private val b = FolderNode("/s/A/B", "B", listOf(c))
    private val a = FolderNode("/s/A", "A", listOf(b))

    @Test
    fun empty_subfolders_get_zero_and_the_counted_ones_keep_their_count() {
        val counts = mapOf("/s/A" to 2)
        assertEquals(
            mapOf("/s/A" to 2, "/s/A/B" to 0, "/s/A/B/C" to 0),
            FolderCounts.zeroFill(a, counts),
        )
    }

    @Test
    fun a_folder_with_photos_deep_down_keeps_its_parents_visible() {
        // A photo in C: C, B and A all have a count, none is zero.
        val counts = mapOf("/s/A" to 1, "/s/A/B" to 1, "/s/A/B/C" to 1)
        assertEquals(counts, FolderCounts.zeroFill(a, counts))
    }

    @Test
    fun a_hidden_empty_branch_stays_hidden_under_its_parent() {
        val counts = FolderCounts.zeroFill(a, emptyMap())
        assertEquals(0, counts["/s/A/B"])
        assertEquals(0, counts["/s/A/B/C"])
        assertEquals(0, counts["/s/A"])
    }
}
