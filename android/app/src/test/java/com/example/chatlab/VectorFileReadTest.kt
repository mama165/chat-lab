package com.example.chatlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VectorFileReadTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val dimension = 4

    @Test
    fun aStoredRowIsRead() {
        val file = File(folder.root, "vectors.bin")
        val vectors = VectorFile(file, dimension)
        vectors.writeAll(listOf(0L to floatArrayOf(1f, 2f, 3f, 4f), 1L to floatArrayOf(5f, 6f, 7f, 8f)))
        assertArrayEquals(floatArrayOf(5f, 6f, 7f, 8f), vectors.readOrNull(1L)!!, 0f)
    }

    @Test
    fun aRowBeyondTheFileGivesNothingInsteadOfAnError() {
        val file = File(folder.root, "vectors.bin")
        VectorFile(file, dimension).writeAll(listOf(0L to floatArrayOf(1f, 2f, 3f, 4f)))
        assertNull(VectorFile(file, dimension).readOrNull(3L))
    }

    @Test
    fun aMissingFileGivesNothingInsteadOfAnError() {
        // The file is erased by "Tout effacer" while the table still has rows: the search must not crash.
        assertNull(VectorFile(File(folder.root, "vectors.bin"), dimension).readOrNull(0L))
    }
}
