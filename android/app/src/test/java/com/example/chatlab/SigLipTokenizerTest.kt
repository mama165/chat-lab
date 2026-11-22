package com.example.chatlab

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class SigLipTokenizerTest {
    /** Un tout petit vocabulaire : a, b, leurs fusions, l'espace « ▁ », et l'octet de « c ». */
    private val json = """
        {"model": {
          "vocab": {"<pad>": 0, "<eos>": 1, "▁": 2, "a": 3, "b": 4, "ab": 5, "▁ab": 6, "<0x63>": 7},
          "merges": ["a b", ["▁", "ab"]]
        }}
    """.trimIndent()

    private val tokenizer = SigLipTokenizer(json, maxLength = 6)

    @Test
    fun mergesTheFirstWordWithoutSpaceMarker() {
        assertArrayEquals(intArrayOf(5, 1, 0, 0, 0, 0), tokenizer.encode("ab"))
    }

    @Test
    fun followingWordsStartWithTheSpaceMarker() {
        assertArrayEquals(intArrayOf(5, 6, 1, 0, 0, 0), tokenizer.encode("ab ab"))
    }

    @Test
    fun lowercasesFirst() {
        assertArrayEquals(tokenizer.encode("ab"), tokenizer.encode("AB"))
    }

    @Test
    fun unknownSymbolsFallBackOnTheirBytes() {
        assertArrayEquals(intArrayOf(7, 1, 0, 0, 0, 0), tokenizer.encode("c"))
    }

    @Test
    fun truncatesAndAlwaysEndsWithEos() {
        val ids = tokenizer.encode("ab ab ab ab ab ab ab")
        assertEquals(6, ids.size)
        assertEquals(1, ids.last())
    }

    @Test
    fun resultIsAlwaysMaxLength() {
        assertEquals(6, tokenizer.encode("").size)
        assertEquals(1, tokenizer.encode("")[0])
    }
}
