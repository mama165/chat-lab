package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

/** The streaming word search of the JSON must give the same photos, in the same order, as Library.searchWords. */
class LibraryWordTest {
    private fun entry(id: String, text: String, indoor: Boolean?) = JSONObject()
        .put("id", id)
        .put("source", "content://media/$id")
        .put("path", "content://media/$id")
        .put("name", "photo_$id.jpg")
        .put("text", text)
        .apply { if (indoor != null) put("indoor", indoor) }
        .put("vectors", JSONArray().put(JSONArray(List(768) { 0.1 })))

    private val json = JSONArray(listOf(
        entry("1", "Facture EDF électricité", true),
        entry("2", "Ticket de caisse", false),
        entry("3", "facture facture", null),
        entry("4", "rien de particulier", true),
        entry("5", "Facture de gaz", false),
    )).toString()

    private fun expected(words: List<String>, place: Place): List<String> =
        Library.searchWords(Library.parse(json), words.joinToString(" "), place).map { it.entry.source }

    @Test
    fun theOneWordSearchMatchesTheLibrary() {
        val words = listOf("facture")
        assertEquals(expected(words, Place.ALL), LibraryCheck.wordMatches(StringReader(json), words, Place.ALL))
    }

    @Test
    fun aTwoWordSearchMatchesTheLibrary() {
        val words = listOf("facture", "edf")
        assertEquals(expected(words, Place.ALL), LibraryCheck.wordMatches(StringReader(json), words, Place.ALL))
    }

    @Test
    fun thePlaceFilterMatchesTheLibrary() {
        val words = listOf("facture")
        assertEquals(expected(words, Place.INDOOR), LibraryCheck.wordMatches(StringReader(json), words, Place.INDOOR))
        assertEquals(expected(words, Place.OUTDOOR), LibraryCheck.wordMatches(StringReader(json), words, Place.OUTDOOR))
    }

    @Test
    fun theFirstWordIsNormalisedAndLongEnough() {
        assertEquals("facture", LibraryCheck.firstWord(StringReader(json)))
    }
}

class SameSetTest {
    @Test
    fun theSamePhotosInAnyOrderAreTheSameSet() {
        assertEquals(true, LibraryCheck.sameSet(listOf("a", "b", "c"), listOf("c", "a", "b")))
    }

    @Test
    fun aDifferentPhotoOrACountDifferenceIsNotTheSameSet() {
        assertEquals(false, LibraryCheck.sameSet(listOf("a", "b"), listOf("a", "c")))
        assertEquals(false, LibraryCheck.sameSet(listOf("a", "a"), listOf("a")))
    }
}
