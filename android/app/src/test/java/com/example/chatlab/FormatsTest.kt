package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class FormatsTest {
    @Test
    fun sizes() {
        assertEquals("512 o", Formats.size(512))
        assertEquals("820 Ko", Formats.size(820L * 1024))
        assertEquals("4 Mo", Formats.size(4L * 1024 * 1024))
        assertEquals("4,5 Mo", Formats.size((4.5 * 1024 * 1024).toLong()))
        assertEquals("94 Mo", Formats.size(94L * 1024 * 1024))
        assertEquals("1,2 Go", Formats.size((1.2 * 1024 * 1024 * 1024).toLong()))
    }

    @Test
    fun photos() {
        assertEquals("aucune photo", Formats.photos(0))
        assertEquals("1 photo", Formats.photos(1))
        assertEquals("215 photos", Formats.photos(215))
    }

    @Test
    fun results() {
        assertEquals("0 résultat", Formats.results(0))
        assertEquals("1 résultat", Formats.results(1))
        assertEquals("4 résultats", Formats.results(4))
    }
}
