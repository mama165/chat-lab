package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextExtraDataTest {
    @Test
    fun the_journal_keeps_the_number_of_words_of_each_pass_and_never_the_words() {
        val data = textExtraData(mapOf(1 to listOf("facture", "42"), 2 to listOf("42")))
        val passes = data.getJSONObject("passes")
        assertEquals(2, passes.getInt("1"))
        assertEquals(1, passes.getInt("2"))
        assertFalse(data.toString().contains("facture"))
        assertFalse(data.has("file"))
    }
}
