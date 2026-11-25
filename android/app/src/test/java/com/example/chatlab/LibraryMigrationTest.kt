package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.StringReader

class LibraryMigrationTest {
    private fun entry(id: String, vectorSize: Int = 768) = JSONObject()
        .put("id", id)
        .put("source", "content://media/external/images/media/$id")
        .put("path", "content://media/external/images/media/$id")
        .put("name", "photo_$id.jpg")
        .put("size", 2048)
        .put("modified", 1700000000000L)
        .put("analysis_version", 2)
        .put("faces", 1)
        .put("portrait", false)
        .put("indoor", true)
        .put("text", "Facture EDF")
        .put("folder", "Camera")
        .put("mime", "image/jpeg")
        .put("taken", "2026-11-03 18:42")
        .put("taken_ms", 1793737320000L)
        .put("added_at", "2026-11-03T17:45:12Z")
        .put("vectors", JSONArray().put(JSONArray(List(vectorSize) { 0.25 })))

    private fun library(vararg entries: JSONObject) = JSONArray(entries.toList()).toString()

    private class Recorder : RecordSink {
        val batches = ArrayList<List<PhotoRecord>>()
        override fun add(records: List<PhotoRecord>) {
            batches += records
        }
    }

    @Test
    fun everyValidEntryIsCopiedAndTheBadOnesAreCounted() {
        val json = library(
            entry("1"),
            entry("2"),
            JSONObject(entry("3").toString()).apply { remove("source") },
            entry("4", vectorSize = 10),
            entry("5"),
        )
        val sink = Recorder()
        val report = LibraryMigration.run(StringReader(json), sink, now = 1L)
        assertEquals(3, report.migrated)
        assertEquals(2, report.skipped)
        assertEquals(3, sink.batches.sumOf { it.size })
    }

    @Test
    fun theRecordsComeInBatches() {
        val json = library(entry("1"), entry("2"), entry("3"), entry("4"), entry("5"))
        val sink = Recorder()
        LibraryMigration.run(StringReader(json), sink, now = 1L, batchSize = 2)
        // Five records, by batches of two: three calls, with batches of 2, 2 and 1.
        assertEquals(listOf(2, 2, 1), sink.batches.map { it.size })
    }

    @Test
    fun anEmptyLibraryMigratesNothing() {
        val sink = Recorder()
        val report = LibraryMigration.run(StringReader("[]"), sink, now = 1L)
        assertEquals(0, report.migrated)
        assertEquals(0, report.skipped)
        assertEquals(0, sink.batches.size)
    }

    @Test
    fun aCopiedEntryKeepsItsFactsAndItsVector() {
        val sink = Recorder()
        LibraryMigration.run(StringReader(library(entry("9"))), sink, now = 1L)
        val record = sink.batches.single().single()
        assertEquals("photo_9.jpg", record.name)
        assertEquals("Facture EDF", record.text)
        assertEquals(768, record.vector.size)
        assertEquals(0.25f, record.vector[0], 0f)
    }
}
