package com.example.chatlab

import java.util.Locale
import kotlin.math.roundToInt

/**
 * The rows of the detail page of an analysis, as the design shows them: the context and the
 * technical figures. Only what the file keeps is shown; a figure that is not kept is "–".
 */
object AnalysisTechnical {
    private const val MISSING = "–"

    /** How many workers the analysis really used: the most it was allowed at one moment. */
    fun workersUsed(record: AnalysisRecord): Int =
        record.samples.maxOfOrNull { it.workers }?.takeIf { it > 0 } ?: record.workers.coerceAtLeast(1)

    /** The word for a thermal level, as the design writes it. */
    fun thermalWord(level: Int): String = when (level) {
        0 -> "Normal"
        1 -> "Léger"
        2 -> "Modéré"
        3 -> "Élevé"
        4 -> "Critique"
        else -> "D'urgence"
    }

    fun contextRows(record: AnalysisRecord): List<Pair<String, String>> {
        return listOf(
            "Début · fin" to "${ChartMath.clockLabel(record.startMs, 0)} · ${ChartMath.clockLabel(record.startMs, (record.durationMs / 1000).toInt())}",
            "Niveau · workers" to "${AnalysisLevel.fromKey(record.level).label} · ${record.workers}",
            "Workers au départ" to startWorkersText(record),
            "Version de l'appli" to record.appVersion,
            "Téléphone · Android" to "${record.device} · ${record.androidVersion}",
            "État thermique (max)" to thermalWord(record.summary().thermalMax),
        )
    }

    /**
     * The average time of each stage for one photo: decoding · image · faces · text, in ms.
     * A dash while no photo or no stage time is kept.
     */
    fun stageAverages(record: AnalysisRecord): String {
        val photos = record.analyzed
        val stages = listOf(StageTimes.DECODE, StageTimes.IMAGE, StageTimes.FACES, StageTimes.TEXT)
        if (photos <= 0 || stages.any { record.stages[it] == null }) return MISSING
        return stages.joinToString(" · ") { grouped((record.stages.getValue(it).toDouble() / photos).roundToInt()) } + " ms"
    }

    /** 1450 becomes "1 450". */
    private fun grouped(value: Int): String = value.toString().reversed().chunked(3).joinToString(" ").reversed()

    /** The workers at the start, with the memory free then: what limited the run, not the level's number. */
    fun startWorkersText(record: AnalysisRecord): String = when {
        record.startWorkers <= 0 -> MISSING
        record.memoryAtStartMb > 0 -> "${record.startWorkers} · ${grouped(record.memoryAtStartMb.toInt())} Mo libres"
        else -> "${record.startWorkers}"
    }

    fun technicalRows(record: AnalysisRecord): List<Pair<String, String>> {
        val s = record.summary()
        val firstStart = record.modelLoads.firstOrNull { !it.reload }
        val lastSample = record.samples.lastOrNull()
        return listOf(
            "Décodage · image · visages · texte" to stageAverages(record),
            "Démarrage des modèles" to (firstStart?.let { String.format(Locale.FRANCE, "%.1f s", it.millis / 1000.0) } ?: MISSING),
            "CPU de l'appli (moyenne · pic)" to "${s.avgCpu.roundToInt()} % · ${s.peakCpu.roundToInt()} %",
            "Mémoire (pic)" to "${s.peakMemMb} Mo",
            "Batterie · température" to
                (if (lastSample == null) MISSING else "${lastSample.batteryPct} % · ${lastSample.batteryTempC.roundToInt()} °C"),
            // The number of threads is not kept yet.
            "Cœurs · threads" to "${record.cores} · $MISSING",
        )
    }
}
