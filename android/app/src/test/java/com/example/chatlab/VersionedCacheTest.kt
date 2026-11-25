package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Test

class VersionedCacheTest {
    @Test
    fun the_value_is_loaded_once_for_the_same_version() {
        var loads = 0
        val cache = VersionedCache { loads++; listOf("a", "b") }
        repeat(50) { cache.get(version = 1) }
        assertEquals(1, loads)
    }

    @Test
    fun the_same_instance_is_returned_while_the_version_is_unchanged() {
        val cache = VersionedCache { ArrayList<String>() }
        val first = cache.get(1)
        assertSame(first, cache.get(1))
    }

    @Test
    fun a_new_version_loads_again() {
        var loads = 0
        val cache = VersionedCache { loads++; loads }
        assertEquals(1, cache.get(1))
        assertEquals(2, cache.get(2))
        assertEquals(2, cache.get(2))
        assertEquals(2, loads)
    }

    @Test
    fun going_back_to_an_older_version_loads_again() {
        var loads = 0
        val cache = VersionedCache { loads++; loads }
        cache.get(1)
        cache.get(2)
        assertEquals(3, cache.get(1))
    }

    @Test
    fun a_failed_load_is_not_kept() {
        var attempts = 0
        val cache = VersionedCache {
            attempts++
            if (attempts == 1) throw IllegalStateException("library not readable")
            "library"
        }
        assertThrows(IllegalStateException::class.java) { cache.get(1) }
        assertEquals("library", cache.get(1))
        assertEquals(2, attempts)
    }
}
