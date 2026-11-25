package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TextExtraDataTest {
    @Test
    fun the_journal_keeps_the_words_of_each_pass_and_not_the_name_of_the_photo() {
        val data = textExtraData(mapOf(1 to listOf("facture", "42"), 2 to listOf("42")))
        assertFalse(data.has("file"))
        assertEquals("facture", data.getJSONObject("passes").getJSONArray("1").getString(0))
        assertEquals("42", data.getJSONObject("passes").getJSONArray("2").getString(0))
    }
}
