package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class TopKTest {
    @Test
    fun keepsTheBestScoresInOrder() {
        val top = TopK<String>(2)
        top.offer("a", 0.1f)
        top.offer("b", 0.9f)
        top.offer("c", 0.5f)
        top.offer("d", 0.2f)
        assertEquals(listOf("b" to 0.9f, "c" to 0.5f), top.sorted())
    }

    @Test
    fun onEqualScoresTheSmallerKeyWins() {
        val top = TopK<String>(2)
        top.offer("z", 0.5f)
        top.offer("m", 0.5f)
        top.offer("a", 0.5f)
        assertEquals(listOf("a" to 0.5f, "m" to 0.5f), top.sorted())
    }

    @Test
    fun aKLargerThanTheStreamKeepsEverything() {
        val top = TopK<Int>(10)
        top.offer(1, 0.3f)
        top.offer(2, 0.7f)
        assertEquals(listOf(2 to 0.7f, 1 to 0.3f), top.sorted())
    }

    @Test
    fun zeroKeepsNothing() {
        val top = TopK<Int>(0)
        top.offer(1, 1f)
        assertEquals(emptyList<Pair<Int, Float>>(), top.sorted())
    }
}
