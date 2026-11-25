package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoRecordFromJsonTest {
    private fun full() = JSONObject()
        .put("source", "content://media/1")
        .put("name", "photo_1.jpg")
        .put("size", 99L)
        .put("modified", 500L)
        .put("analysis_version", 2)
        .put("added_at", "2026-11-03T17:45:12Z")
        .put("vectors", JSONArray().put(JSONArray(List(768) { 0.5 })))

    @Test
    fun aCompleteEntryIsMapped() {
        val r = PhotoRecordFromJson.from(full(), now = 1L)!!
        assertEquals("content://media/1", r.address)
        assertEquals("content://media/1", r.path)
        assertEquals(99L, r.size)
        assertEquals(500L, r.modified)
        assertEquals(2, r.analysisVersion)
        assertEquals(768, r.vector.size)
    }

    @Test
    fun theDateOfAdditionComesFromTheEntry() {
        // 2026-11-03T17:45:12Z, in milliseconds.
        assertEquals(1793727912000L, PhotoRecordFromJson.from(full(), now = 1L)!!.addedAt)
    }

    @Test
    fun anEntryWithoutAddressOrNameIsNotCopied() {
        assertNull(PhotoRecordFromJson.from(full().apply { remove("source") }, now = 1L))
        assertNull(PhotoRecordFromJson.from(full().apply { put("name", "") }, now = 1L))
    }

    @Test
    fun aVectorOfTheWrongSizeIsNotCopied() {
        val wrong = full().put("vectors", JSONArray().put(JSONArray(List(3) { 0.5 })))
        assertNull(PhotoRecordFromJson.from(wrong, now = 1L))
        assertNull(PhotoRecordFromJson.from(full().apply { remove("vectors") }, now = 1L))
    }

    @Test
    fun missingOptionalFieldsBecomeNullsAndDefaults() {
        val r = PhotoRecordFromJson.from(full(), now = 42L)!!
        assertNull(r.folder)
        assertNull(r.mime)
        assertNull(r.taken)
        assertNull(r.takenMs)
        assertNull(r.text)
        assertNull(r.indoor)
        assertFalse(r.portrait)
        assertEquals(0, r.faces)
    }

    @Test
    fun anEntryWithoutAddedDateUsesTheTimeOfTheMigration() {
        val r = PhotoRecordFromJson.from(full().apply { remove("added_at") }, now = 42L)!!
        assertEquals(42L, r.addedAt)
        assertTrue(r.addedAt > 0)
    }
}
