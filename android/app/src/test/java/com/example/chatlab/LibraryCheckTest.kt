package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

class LibraryCheckTest {
    private fun entry(id: String, value: Double = 0.25) = JSONObject()
        .put("source", "content://media/$id")
        .put("name", "photo_$id.jpg")
        .put("vectors", JSONArray().put(JSONArray(List(768) { value })))

    private fun library(vararg entries: JSONObject) = JSONArray(entries.toList()).toString()

    @Test
    fun aStoreThatMatchesTheJsonGivesNoDifference() {
        val rows = mapOf("content://media/1" to 0L, "content://media/2" to 1L)
        val vectors = mapOf(0L to FloatArray(768) { 0.25f }, 1L to FloatArray(768) { 0.25f })
        val report = LibraryCheck.run(StringReader(library(entry("1"), entry("2"))), rows) { vectors.getValue(it) }
        assertEquals(2, report.checked)
        assertEquals(0, report.missing)
        assertEquals(0, report.different)
    }

    @Test
    fun aPhotoWithoutRowIsMissing() {
        val report = LibraryCheck.run(StringReader(library(entry("1"))), emptyMap()) { FloatArray(768) }
        assertEquals(1, report.checked)
        assertEquals(1, report.missing)
    }

    @Test
    fun aStoredVectorThatDiffersIsCounted() {
        val rows = mapOf("content://media/1" to 0L)
        val report = LibraryCheck.run(StringReader(library(entry("1", value = 0.25))), rows) { FloatArray(768) { 0.5f } }
        assertEquals(1, report.different)
    }

    @Test
    fun aPhotoWrittenTwiceInTheJsonIsCountedOnce() {
        val rows = mapOf("content://media/1" to 0L)
        val report = LibraryCheck.run(StringReader(library(entry("1"), entry("1"))), rows) { FloatArray(768) { 0.25f } }
        assertEquals(1, report.checked)
        assertEquals(1, report.duplicates)
    }
}

class LibraryRankTest {
    private fun entry(id: String, value: Double) = JSONObject()
        .put("source", "content://media/$id")
        .put("name", "photo_$id.jpg")
        .put("vectors", JSONArray().put(JSONArray(List(768) { value })))

    @Test
    fun theJsonRankingKeepsTheBestPhotos() {
        val json = JSONArray(listOf(entry("1", 0.1), entry("2", 0.9), entry("3", 0.5))).toString()
        val query = FloatArray(768) { 1f }
        val top = LibraryCheck.rank(StringReader(json), query, k = 2)
        assertEquals(listOf("content://media/2", "content://media/3"), top.map { it.first })
    }

    @Test
    fun theCommonCountIsTheNumberOfAddressesInBothLists() {
        assertEquals(2, LibraryCheck.common(listOf("a", "b", "c"), listOf("c", "a", "z")))
        assertEquals(0, LibraryCheck.common(emptyList(), listOf("a")))
    }
}
