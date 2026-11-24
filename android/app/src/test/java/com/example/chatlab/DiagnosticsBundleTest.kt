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
}
