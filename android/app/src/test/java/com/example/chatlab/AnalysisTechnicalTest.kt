package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisTechnicalTest {
    private val start = 1_795_000_000_000L

    private fun record(workers: Int, samples: List<Sample>, loads: List<ModelLoad> = emptyList()) = AnalysisRecord(
        startMs = start,
        endMs = start + 600_000L,
        level = "maximal",
        workers = workers,
        stopped = false,
        appVersion = "a5391be",
        device = "Pixel 10a",
        androidVersion = 16,
        cores = 8,
        folders = listOf(FolderStat("Camera", 10, 1, 0, 0)),
        samples = samples,
        modelLoads = loads,
    )

    private fun sample(t: Int, workers: Int, thermal: Int = 2) =
        Sample(t, cpuPercent = 60f, memMb = 700 + t, batteryPct = 90 - t, batteryTempC = 36.4f, thermal = thermal, workers = workers)

    @Test
    fun only_the_workers_really_used_are_counted() {
        val two = record(4, listOf(sample(0, 2), sample(60, 2), sample(120, 1)))
        assertEquals(2, AnalysisTechnical.workersUsed(two))
        val four = record(4, listOf(sample(0, 4), sample(60, 3)))
        assertEquals(4, AnalysisTechnical.workersUsed(four))
    }

    @Test
    fun an_old_record_without_workers_falls_back_to_its_level() {
        assertEquals(3, AnalysisTechnical.workersUsed(record(3, listOf(sample(0, 0)))))
        assertEquals(1, AnalysisTechnical.workersUsed(record(0, emptyList())))
    }

    @Test
    fun the_context_shows_the_workers_from_start_to_end() {
        val rows = AnalysisTechnical.contextRows(record(4, listOf(sample(0, 4), sample(300, 1))))
        assertEquals("Maximum · 4 → 1", rows.first { it.first == "Niveau · workers" }.second)
        assertEquals("Modéré", rows.first { it.first == "État thermique (max)" }.second)
    }

    @Test
    fun the_technical_rows_match_the_design_and_keep_only_what_is_known() {
        val loads = listOf(
            ModelLoad(reload = false, atMs = start + 2_000L, millis = 2100L),
            ModelLoad(reload = true, atMs = start + 300_000L, millis = 1800L),
        )
        val rows = AnalysisTechnical.technicalRows(record(4, listOf(sample(0, 4), sample(10, 4)), loads)).toMap()
        assertEquals(
            listOf(
                "Décodage · image · visages · texte",
                "Démarrage des modèles",
                "CPU de l'appli (moyenne · pic)",
                "Mémoire (pic)",
                "Batterie · température",
                "Cœurs · threads",
            ),
            rows.keys.toList(),
        )
        assertEquals("–", rows["Décodage · image · visages · texte"])
        assertEquals("2,1 s", rows["Démarrage des modèles"])
        assertEquals("60 % · 60 %", rows["CPU de l'appli (moyenne · pic)"])
        assertEquals("710 Mo", rows["Mémoire (pic)"])
        assertEquals("80 % · 36 °C", rows["Batterie · température"])
        assertEquals("8 · –", rows["Cœurs · threads"])
    }

    @Test
    fun thermal_levels_have_their_words() {
        assertEquals("Normal", AnalysisTechnical.thermalWord(0))
        assertEquals("Élevé", AnalysisTechnical.thermalWord(3))
        assertEquals("D'urgence", AnalysisTechnical.thermalWord(6))
    }
}
