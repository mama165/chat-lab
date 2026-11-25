package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class TextWordsTest {
    @Test
    fun new_words_are_counted_and_known_ones_are_not() {
        val words = LinkedHashMap<String, String>()
        assertEquals(3, TextWords.add(words, "Bonjour le monde"))
        assertEquals(1, TextWords.add(words, "monde Paris"))
        assertEquals(0, TextWords.add(words, "Paris"))
    }

    @Test
    fun the_case_is_kept_for_the_first_form_only() {
        val words = LinkedHashMap<String, String>()
        TextWords.add(words, "Paris")
        TextWords.add(words, "paris")
        assertEquals(mapOf("paris" to "Paris"), words)
    }

    @Test
    fun a_capital_i_before_an_apostrophe_reads_as_an_l() {
        val words = LinkedHashMap<String, String>()
        TextWords.add(words, "I'herbe")
        assertEquals(setOf("l'herbe"), words.keys)
    }

    @Test
    fun a_lone_symbol_or_digit_is_noise_and_blanks_are_skipped() {
        val words = LinkedHashMap<String, String>()
        assertEquals(1, TextWords.add(words, "% 0 a   "))
        assertEquals(setOf("a"), words.keys)
    }

    @Test
    fun the_new_words_are_listed_as_they_were_read() {
        val words = LinkedHashMap<String, String>()
        TextWords.add(words, "Bonjour le monde")
        assertEquals(listOf("Paris", "Lyon"), TextWords.addNew(words, "Paris monde Lyon"))
        assertEquals(emptyList<String>(), TextWords.addNew(words, "Paris"))
    }
}
