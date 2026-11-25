package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMemoryTest {
    private val mb = 1024L * 1024L

    @Test
    fun bytes_become_megabytes_rounded_down() {
        assertEquals(0L, NativeMemory.mb(mb - 1))
        assertEquals(1L, NativeMemory.mb(mb))
        assertEquals(1_300L, NativeMemory.mb(1_300L * mb + 512L))
    }

    @Test
    fun a_step_adds_its_memory() {
        assertEquals(372L, NativeMemory.deltaMb(100L * mb, 472L * mb))
    }

    @Test
    fun a_step_that_frees_memory_gives_zero_not_a_negative_number() {
        assertEquals(0L, NativeMemory.deltaMb(500L * mb, 100L * mb))
    }

    @Test
    fun the_memory_is_written_for_the_first_photos_and_some_later_ones() {
        assertTrue(NativeMemory.isPhotoPoint(1))
        assertTrue(NativeMemory.isPhotoPoint(2))
        assertTrue(NativeMemory.isPhotoPoint(10))
        assertTrue(NativeMemory.isPhotoPoint(60))
        assertFalse(NativeMemory.isPhotoPoint(3))
        assertFalse(NativeMemory.isPhotoPoint(0))
    }
}
