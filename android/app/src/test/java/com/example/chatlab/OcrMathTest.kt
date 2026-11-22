package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class OcrMathTest {
    private val vocabulary = listOf("blank", "a", "b", " ")

    private fun step(vararg values: Float) = values

    @Test
    fun decodeSkipsBlanksAndRepeats() {
        val steps = arrayOf(
            step(0.1f, 0.9f, 0f, 0f), // a
            step(0.1f, 0.8f, 0f, 0f), // a répété
            step(0.9f, 0f, 0f, 0f), // blanc
            step(0f, 0.7f, 0.1f, 0f), // a de nouveau
            step(0f, 0f, 0.6f, 0f), // b
        )
        val (text, confidence) = OcrMath.decode(steps, vocabulary)
        assertEquals("aab", text)
        assertEquals((0.9f + 0.7f + 0.6f) / 3, confidence, 1e-5f)
    }

    @Test
    fun decodeOfOnlyBlanksIsEmpty() {
        val (text, confidence) = OcrMath.decode(arrayOf(step(1f, 0f), step(1f, 0f)), vocabulary)
        assertEquals("", text)
        assertEquals(0f, confidence, 0f)
    }

    @Test
    fun decodeIgnoresIndexesOutsideTheVocabulary() {
        val (text, _) = OcrMath.decode(arrayOf(step(0f, 0f, 0f, 0f, 1f)), vocabulary)
        assertEquals("", text)
    }

    @Test
    fun decodeHandlesEmptyInput() {
        assertEquals("", OcrMath.decode(emptyArray(), vocabulary).first)
        assertEquals("", OcrMath.decode(arrayOf(FloatArray(0)), vocabulary).first)
    }

    @Test
    fun roundTo32() {
        assertEquals(32, OcrMath.roundTo32(0.0))
        assertEquals(32, OcrMath.roundTo32(40.0))
        assertEquals(64, OcrMath.roundTo32(50.0))
        assertEquals(736, OcrMath.roundTo32(736.0))
    }
}
