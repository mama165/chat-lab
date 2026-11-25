package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class TypeCountsTest {
    @Test
    fun photos_are_counted_by_their_type() {
        val counts = TypeCounts.of(listOf("image/jpeg", "image/png", "image/jpeg", "image/webp"))
        assertEquals(mapOf("JPEG" to 2, "PNG" to 1, "WebP" to 1), counts)
    }

    @Test
    fun other_or_empty_types_are_grouped_and_nothing_is_invented() {
        assertEquals(mapOf("Autre" to 2), TypeCounts.of(listOf("", "image/heic")))
        assertEquals(emptyMap<String, Int>(), TypeCounts.of(emptyList()))
    }

    @Test
    fun result_is_sorted_by_label_for_a_stable_file() {
        assertEquals(listOf("Autre", "JPEG", "PNG", "WebP"), TypeCounts.of(listOf("image/webp", "image/png", "image/jpeg", "x")).keys.toList())
    }
}
