package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryExportTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun aCompleteExportReplacesThePreviousBackup() {
        val dir = folder.newFolder("snapshots")
        File(dir, "library.json").writeText("[old]")
        LibraryExport.replaceAtomically(dir, "library.json") { out -> out.write("[new]") }
        assertEquals("[new]", File(dir, "library.json").readText())
        assertFalse("no temporary file is left", File(dir, "library.json.tmp").exists())
    }

    @Test
    fun aFailedExportKeepsThePreviousBackup() {
        val dir = folder.newFolder("snapshots")
        File(dir, "library.json").writeText("[previous backup]")
        try {
            LibraryExport.replaceAtomically(dir, "library.json") { out ->
                out.write("[partial")
                error("disk full")
            }
            fail("the failure must be reported")
        } catch (e: IllegalStateException) {
            assertEquals("disk full", e.message)
        }
        assertEquals("[previous backup]", File(dir, "library.json").readText())
        assertFalse("the partial file is removed", File(dir, "library.json.tmp").exists())
    }

    @Test
    fun aFirstExportCreatesTheBackupAndItsFolder() {
        val dir = File(folder.root, "ChatLab/snapshots")
        LibraryExport.replaceAtomically(dir, "library.json") { out -> out.write("[]") }
        assertEquals("[]", File(dir, "library.json").readText())
    }

    @Test
    fun theEntriesAreAJsonArrayWithTheirVectors() {
        val out = java.io.StringWriter()
        val vector = FloatArray(3) { it * 0.5f }
        val count = LibraryExport.writeEntries(out) { emit ->
            emit(JSONObject().put("id", "/s/a.jpg").put("name", "a.jpg"), vector)
            emit(JSONObject().put("id", "/s/b.jpg").put("name", "b.jpg"), vector)
        }
        assertEquals(2, count)
        val array = JSONArray(out.toString())
        assertEquals(2, array.length())
        val first = array.getJSONObject(0)
        assertEquals("a.jpg", first.getString("name"))
        assertEquals(1.0, first.getJSONArray("vectors").getJSONArray(0).getDouble(2), 0.0001)
    }

    @Test
    fun anEmptyLibraryGivesAnEmptyArray() {
        val out = java.io.StringWriter()
        val count = LibraryExport.writeEntries(out) { _ -> }
        assertEquals(0, count)
        assertEquals(0, JSONArray(out.toString()).length())
        assertTrue(out.toString() == "[]")
    }
}
