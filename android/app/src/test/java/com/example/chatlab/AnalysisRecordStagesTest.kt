package com.example.chatlab

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

class AnalysisRecordStagesTest {
    private val zone = TimeZone.getTimeZone("Europe/Paris")
    private val start = 1_795_000_000_000L

    private fun record(loads: List<ModelLoad>, types: Map<String, Int>) = AnalysisRecord(
        startMs = start,
        endMs = start + 600_000L,
        level = "puissant",
        workers = 3,
        stopped = false,
        appVersion = "1.0 (1)",
        device = "Google Pixel 10a",
        androidVersion = 36,
        cores = 8,
        folders = listOf(FolderStat("Camera", 10, 1, 0, 0)),
        samples = emptyList(),
        types = types,
        modelLoads = loads,
    )

    @Test
    fun types_and_model_loads_survive_a_round_trip() {
        val original = record(
            loads = listOf(
                ModelLoad(reload = false, atMs = start + 2_000L, millis = 2100L),
                ModelLoad(reload = true, atMs = start + 300_000L, millis = 1800L),
            ),
            types = mapOf("JPEG" to 7, "PNG" to 3),
        )
        val back = AnalysisRecord.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(mapOf("JPEG" to 7, "PNG" to 3), back.types)
        assertEquals(2, back.modelLoads.size)
        assertEquals(false, back.modelLoads[0].reload)
        assertEquals(2100L, back.modelLoads[0].millis)
        assertEquals(true, back.modelLoads[1].reload)
        // The time of a load is kept to the second, relative to the start of the run.
        assertEquals(300_000L, back.modelLoads[1].atMs - back.startMs)
        assertEquals("puissant", back.level)
        assertEquals(3, back.workers)
    }

    @Test
    fun a_file_written_before_these_fields_still_opens() {
        val old = record(emptyList(), emptyMap()).toJson()
        old.remove("types")
        old.remove("models")
        val back = AnalysisRecord.fromJson(old)
        assertEquals(emptyMap<String, Int>(), back.types)
        assertTrue(back.modelLoads.isEmpty())
        assertEquals(10, back.analyzed)
    }

    @Test
    fun the_time_of_each_model_survives_the_file() {
        val parts = mapOf("Modèle des images" to 640L, "Modèle des textes" to 980L, "Détection des visages" to 120L)
        val original = record(listOf(ModelLoad(reload = true, atMs = start + 5_000L, millis = 1_740L, parts = parts)), emptyMap())
        val back = AnalysisRecord.fromJson(JSONObject(original.toJson().toString()))
        assertEquals(parts, back.modelLoads.single().parts)
    }

    @Test
    fun the_text_copied_reads_back_as_the_same_analysis() {
        val original = record(
            loads = listOf(ModelLoad(reload = true, atMs = start + 9_000L, millis = 1_740L, parts = mapOf("Modèle des images" to 640L))),
            types = mapOf("JPEG" to 812, "PNG" to 40),
        )
        // The clipboard gets exactly what the file holds: the pretty JSON written by the app.
        val copied = original.toJson().toString(2)
        val back = AnalysisRecord.fromJson(JSONObject(copied))
        assertEquals(original.level, back.level)
        assertEquals(original.types, back.types)
        assertEquals(original.modelLoads.single().parts, back.modelLoads.single().parts)
        assertEquals(original.analyzed, back.analyzed)
        assertTrue(copied.contains("\n"))
    }
}
