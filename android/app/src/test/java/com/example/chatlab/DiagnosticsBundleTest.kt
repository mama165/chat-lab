package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsBundleTest {
    private val analysis = JSONObject().put("run", JSONObject().put("workers", 4))

    @Test
    fun the_bundle_holds_the_analysis_the_run_and_the_recent_application_lines() {
        val run = listOf("""{"kind":"run","data":{"workers":4}}""", """{"kind":"workersAtStart"}""")
        val session = (1..400).map { """{"kind":"appStart","n":$it}""" }
        val bundle = Diagnostics.bundle(analysis, run, session)
        assertEquals(4, bundle.getJSONObject("analysis").getJSONObject("run").getInt("workers"))
        val diagnostics = bundle.getJSONObject("diagnostics")
        assertEquals(2, diagnostics.getJSONArray("run").length())
        assertEquals("workersAtStart", diagnostics.getJSONArray("run").getJSONObject(1).getString("kind"))
        // Only the most recent lines of the application's journal are kept.
        val kept = diagnostics.getJSONArray("session")
        assertEquals(Diagnostics.SESSION_LINES, kept.length())
        assertEquals(400, kept.getJSONObject(kept.length() - 1).getInt("n"))
    }

    @Test
    fun a_line_that_is_not_json_is_left_out_not_fatal() {
        val bundle = Diagnostics.bundle(analysis, listOf("pas du json", """{"kind":"ok"}"""), emptyList())
        assertEquals(1, bundle.getJSONObject("diagnostics").getJSONArray("run").length())
    }

    @Test
    fun the_analysis_names_its_run_journal_and_keeps_it_on_disk() {
        val record = AnalysisRecord(
            startMs = 1_795_000_000_000L,
            endMs = 1_795_000_600_000L,
            level = "maximal",
            workers = 4,
            stopped = false,
            appVersion = "a5391be",
            device = "Pixel 10a",
            androidVersion = 16,
            cores = 8,
            folders = emptyList(),
            samples = emptyList(),
            diagnosticsRun = "run-20261124-101500.jsonl",
        )
        val back = AnalysisRecord.fromJson(JSONObject(record.toJson().toString()))
        assertEquals("run-20261124-101500.jsonl", back.diagnosticsRun)
    }

    @Test
    fun the_start_workers_and_memory_survive_a_round_trip() {
        val record = AnalysisRecord(
            startMs = 1_795_000_000_000L,
            endMs = 1_795_000_600_000L,
            level = "maximal",
            workers = 4,
            stopped = false,
            appVersion = "a5391be",
            device = "Pixel 10a",
            androidVersion = 16,
            cores = 8,
            folders = emptyList(),
            samples = emptyList(),
            startWorkers = 1,
            memoryAtStartMb = 1_900L,
        )
        val back = AnalysisRecord.fromJson(JSONObject(record.toJson().toString()))
        assertEquals(1, back.startWorkers)
        assertEquals(1_900L, back.memoryAtStartMb)
    }

    private val paris = java.util.TimeZone.getTimeZone("Europe/Paris")

    private fun startOf(stamp: String): Long =
        java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).apply { timeZone = paris }.parse(stamp)!!.time

    @Test
    fun the_journal_started_nearest_to_the_analysis_is_chosen() {
        val start = startOf("20261124-150707")
        val names = listOf(
            "run-20261124-150650.jsonl",
            "run-20261124-120000.jsonl",
            "run-20261124-152000.jsonl",
        )
        assertEquals("run-20261124-150650.jsonl", Diagnostics.nearestRun(names, start, paris))
    }

    @Test
    fun no_journal_within_ten_minutes_means_none() {
        val start = startOf("20261124-150707")
        assertEquals("", Diagnostics.nearestRun(listOf("run-20261124-120000.jsonl"), start, paris))
    }

    @Test
    fun files_that_are_not_run_journals_are_ignored() {
        val start = startOf("20261124-150707")
        val names = listOf("session.jsonl", "exit-1795.trace.txt", "run-20261124-150700.json", "run-bad.jsonl")
        assertEquals("", Diagnostics.nearestRun(names, start, paris))
    }
}
