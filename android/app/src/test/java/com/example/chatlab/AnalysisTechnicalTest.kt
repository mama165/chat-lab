package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisTechnicalTest {
    private val start = 1_795_000_000_000L

    private fun record(
        workers: Int,
        samples: List<Sample>,
        loads: List<ModelLoad> = emptyList(),
        stages: Map<String, Long> = emptyMap(),
        startWorkers: Int = 0,
        memoryAtStartMb: Long = 0L,
    ) = AnalysisRecord(
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
        stages = stages,
        startWorkers = startWorkers,
        memoryAtStartMb = memoryAtStartMb,
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
        // The level's number, not a reduction: the run's own start is shown on its own row.
        assertEquals("Maximum · 4", rows.first { it.first == "Niveau · workers" }.second)
        assertEquals("Modéré", rows.first { it.first == "État thermique (max)" }.second)
    }

    @Test
    fun the_start_workers_show_the_memory_that_limited_them() {
        val limited = record(4, listOf(sample(0, 1), sample(300, 1)), startWorkers = 1, memoryAtStartMb = 1_900)
        assertEquals("1 · 1 900 Mo libres", AnalysisTechnical.startWorkersText(limited))
        val rows = AnalysisTechnical.contextRows(limited).map { it.first }
        assertEquals(listOf("Début · fin", "Niveau · workers", "Workers au départ", "Version de l'appli", "Téléphone · Android", "État thermique (max)"), rows)
    }

    @Test
    fun an_analysis_without_the_start_workers_shows_a_dash() {
        assertEquals("–", AnalysisTechnical.startWorkersText(record(4, emptyList())))
        assertEquals("3", AnalysisTechnical.startWorkersText(record(4, emptyList(), startWorkers = 3)))
    }

    @Test
    fun the_technical_rows_match_the_design_and_keep_only_what_is_known() {
        val loads = listOf(
            ModelLoad(reload = false, atMs = start + 2_000L, millis = 2100L),
            ModelLoad(reload = true, atMs = start + 300_000L, millis = 1800L),
        )
        // Ten photos in the folder: the totals are divided by ten.
        val stages = mapOf(StageTimes.DECODE to 2_100L, StageTimes.IMAGE to 8_400L, StageTimes.FACES to 3_100L, StageTimes.TEXT to 14_500L)
        val rows = AnalysisTechnical.technicalRows(record(4, listOf(sample(0, 4), sample(10, 4)), loads, stages)).toMap()
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
        assertEquals("210 · 840 · 310 · 1 450 ms", rows["Décodage · image · visages · texte"])
        assertEquals("2,1 s", rows["Démarrage des modèles"])
        assertEquals("60 % · 60 %", rows["CPU de l'appli (moyenne · pic)"])
        assertEquals("710 Mo", rows["Mémoire (pic)"])
        assertEquals("80 % · 36 °C", rows["Batterie · température"])
        assertEquals("8 · –", rows["Cœurs · threads"])
    }

    @Test
    fun the_stage_times_are_dashes_until_they_are_kept() {
        assertEquals("–", AnalysisTechnical.stageAverages(record(4, emptyList())))
        val partial = record(4, emptyList(), stages = mapOf(StageTimes.IMAGE to 900L))
        assertEquals("–", AnalysisTechnical.stageAverages(partial))
    }

    @Test
    fun stage_times_survive_a_round_trip() {
        val original = record(4, emptyList(), stages = mapOf(StageTimes.TEXT to 1_450L))
        val back = AnalysisRecord.fromJson(org.json.JSONObject(original.toJson().toString()))
        assertEquals(mapOf(StageTimes.TEXT to 1_450L), back.stages)
    }

    @Test
    fun thermal_levels_have_their_words() {
        assertEquals("Normal", AnalysisTechnical.thermalWord(0))
        assertEquals("Élevé", AnalysisTechnical.thermalWord(3))
        assertEquals("D'urgence", AnalysisTechnical.thermalWord(6))
    }
}
