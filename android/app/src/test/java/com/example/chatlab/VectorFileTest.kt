package com.example.chatlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VectorFileTest {
    @get:Rule val folder = TemporaryFolder()

    private fun vector(seed: Float) = FloatArray(768) { seed + it * 0.001f }

    @Test
    fun aVectorReadsBackAsItWasWritten() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        val v = vector(0.5f)
        file.writeAll(listOf(0L to v))
        assertArrayEquals(v, file.read(0L), 0f)
    }

    @Test
    fun eachRowIsAtItsOwnOffsetAndDoesNotDisturbTheOthers() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        val a = vector(1f)
        val b = vector(2f)
        val c = vector(3f)
        file.writeAll(listOf(0L to a, 2L to c, 1L to b))
        assertArrayEquals(a, file.read(0L), 0f)
        assertArrayEquals(b, file.read(1L), 0f)
        assertArrayEquals(c, file.read(2L), 0f)
    }

    @Test
    fun aRowWrittenAgainIsOverwrittenInPlace() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        file.writeAll(listOf(0L to vector(1f), 1L to vector(2f)))
        val replaced = vector(9f)
        file.writeAll(listOf(0L to replaced))
        assertArrayEquals(replaced, file.read(0L), 0f)
        assertEquals(2L * 768 * 4, folder.root.resolve("vectors.bin").length())
    }

    @Test
    fun thePlaceOfARowIsRowTimes3072Bytes() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        file.writeAll(listOf(3L to vector(0f)))
        // Row 3 is written after rows 0 to 2 (3 blocks of 3072 bytes): the file ends at the end of row 3.
        assertEquals(4L * 3072, folder.root.resolve("vectors.bin").length())
    }

    @Test
    fun theBytesAreLittleEndianFloats() {
        // 0.0123 as a 32-bit float, little-endian: F0 85 49 3C (checked with the standard struct module).
        val file = VectorFile(folder.newFile("vectors.bin"))
        file.writeAll(listOf(0L to FloatArray(768) { if (it == 0) 0.0123f else 0f }))
        val raw = folder.root.resolve("vectors.bin").readBytes()
        assertEquals(0xF0.toByte(), raw[0])
        assertEquals(0x85.toByte(), raw[1])
        assertEquals(0x49.toByte(), raw[2])
        assertEquals(0x3C.toByte(), raw[3])
    }

    @Test
    fun aVectorOfTheWrongSizeIsRefused() {
        val file = VectorFile(folder.newFile("vectors.bin"))
        val refused = runCatching { file.writeAll(listOf(0L to FloatArray(10))) }.isFailure
        assertTrue(refused)
    }
}
