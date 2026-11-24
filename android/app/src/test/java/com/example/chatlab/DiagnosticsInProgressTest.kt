package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticsInProgressTest {
    private val progress = JSONObject().put("done", 3).put("total", 10)

    @Test
    fun the_copy_during_a_run_says_so_and_carries_the_progress() {
        val bundle = Diagnostics.inProgressBundle(progress, runLines = emptyList(), sessionLines = emptyList())
        assertTrue(bundle.getBoolean("inProgress"))
        assertEquals(3, bundle.getJSONObject("progress").getInt("done"))
    }

    @Test
    fun the_run_lines_are_kept_in_order_and_an_unreadable_line_is_skipped() {
        val lines = listOf("""{"kind":"run","sinceStartMs":1}""", "not json", """{"kind":"sample","sinceStartMs":2}""")
        val run = Diagnostics.inProgressBundle(progress, lines, emptyList())
            .getJSONObject("diagnostics").getJSONArray("run")
        assertEquals(2, run.length())
        assertEquals("run", run.getJSONObject(0).getString("kind"))
        assertEquals("sample", run.getJSONObject(1).getString("kind"))
    }

    @Test
    fun the_application_journal_keeps_only_its_recent_lines() {
        val session = (1..(Diagnostics.SESSION_LINES + 50)).map { """{"kind":"event","n":$it}""" }
        val kept = Diagnostics.inProgressBundle(progress, emptyList(), session)
            .getJSONObject("diagnostics").getJSONArray("session")
        assertEquals(Diagnostics.SESSION_LINES, kept.length())
        // The oldest lines are dropped, the newest are kept.
        assertEquals(51, kept.getJSONObject(0).getInt("n"))
    }
}
