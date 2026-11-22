package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalsJsonTest {
    @Test
    fun addStoresSourceAndDate() {
        val json = RemovalsJson.add(JSONObject(), "gaufre", listOf("content://a"), "22/11/2026")
        assertEquals(listOf(Removal("gaufre", "content://a", "22/11/2026")), RemovalsJson.all(json))
    }

    @Test
    fun addDoesNotDuplicateOrKeepBlankSources() {
        var json = RemovalsJson.add(JSONObject(), "gaufre", listOf("a", "a", ""), "22/11/2026")
        json = RemovalsJson.add(json, "gaufre", listOf("a", "b"), "23/11/2026")
        assertEquals(setOf("a", "b"), RemovalsJson.sourcesFor(json, "gaufre"))
        assertEquals(2, RemovalsJson.count(json))
    }

    @Test
    fun removalsArePerSearch() {
        var json = RemovalsJson.add(JSONObject(), "gaufre", listOf("a"), "d")
        json = RemovalsJson.add(json, "wifi", listOf("b"), "d")
        assertEquals(setOf("a"), RemovalsJson.sourcesFor(json, "gaufre"))
        assertEquals(setOf("b"), RemovalsJson.sourcesFor(json, "wifi"))
        assertTrue(RemovalsJson.sourcesFor(json, "plage").isEmpty())
    }

    @Test
    fun restoreRemovesOnlyThatPhotoAndDropsEmptySearches() {
        var json = RemovalsJson.add(JSONObject(), "gaufre", listOf("a", "b"), "d")
        json = RemovalsJson.restore(json, "gaufre", "a")
        assertEquals(setOf("b"), RemovalsJson.sourcesFor(json, "gaufre"))
        json = RemovalsJson.restore(json, "gaufre", "b")
        assertFalse(json.has("gaufre"))
        assertEquals(0, RemovalsJson.count(json))
    }

    @Test
    fun restoreOnUnknownSearchChangesNothing() {
        val json = RemovalsJson.add(JSONObject(), "gaufre", listOf("a"), "d")
        RemovalsJson.restore(json, "wifi", "a")
        assertEquals(1, RemovalsJson.count(json))
    }

    @Test
    fun legacyPlainStringsAreStillRead() {
        val json = JSONObject().put("gaufre", JSONArray().put("content://old"))
        assertEquals(listOf(Removal("gaufre", "content://old", "")), RemovalsJson.all(json))
        assertEquals(setOf("content://old"), RemovalsJson.sourcesFor(json, "gaufre"))
        RemovalsJson.restore(json, "gaufre", "content://old")
        assertEquals(0, RemovalsJson.count(json))
    }

    @Test
    fun allIsSortedBySearchThenDate() {
        var json = RemovalsJson.add(JSONObject(), "wifi", listOf("w"), "01/01/2026")
        json = RemovalsJson.add(json, "gaufre", listOf("g2"), "02/01/2026")
        json = RemovalsJson.add(json, "gaufre", listOf("g1"), "01/01/2026")
        assertEquals(listOf("g1", "g2", "w"), RemovalsJson.all(json).map { it.source })
    }
}
