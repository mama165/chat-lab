package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class DistinctByIdTest {
    private data class Item(val id: String, val value: String)

    private fun items(vararg pairs: Pair<String, String>) = pairs.map { Item(it.first, it.second) }

    @Test
    fun `keeps the first record of an identifier by default`() {
        val result = distinctById(items("a" to "1", "b" to "2", "a" to "3"), { it.id })
        assertEquals(listOf(Item("a", "1"), Item("b", "2")), result)
    }

    @Test
    fun `keeps the last record of an identifier when asked`() {
        val result = distinctById(items("a" to "1", "b" to "2", "a" to "3"), { it.id }, keepLast = true)
        assertEquals(listOf(Item("b", "2"), Item("a", "3")), result)
    }

    @Test
    fun `keeps every record with a blank identifier`() {
        val result = distinctById(items("" to "1", "" to "2", "a" to "3"), { it.id })
        assertEquals(listOf(Item("", "1"), Item("", "2"), Item("a", "3")), result)
    }

    @Test
    fun `a list without duplicates is returned unchanged, in the same order`() {
        val input = items("c" to "1", "a" to "2", "b" to "3")
        assertEquals(input, distinctById(input, { it.id }))
    }

    @Test
    fun `an empty list gives an empty list`() {
        assertEquals(emptyList<Item>(), distinctById(emptyList<Item>(), { it.id }))
    }
}
