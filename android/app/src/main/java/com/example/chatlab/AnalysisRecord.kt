package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Un relevé pendant l'analyse : une ligne de la courbe. [tSec] est le temps écoulé depuis le début. */
data class Sample(
    val tSec: Int,
    val cpuPercent: Float,
    val memMb: Int,
    val batteryPct: Int,
    val batteryTempC: Float,
    val thermal: Int,
    /** Workers allowed at that moment; 0 for a record made before this was kept. */
    val workers: Int = 0,
)

/** Ce qui s'est passé dans un dossier pendant l'analyse. */
data class FolderStat(
    val name: String,
    val analyzed: Int,
    val ignored: Int,
    val unread: Int,
    val refused: Int,
)

/** Une analyse terminée, telle qu'elle est écrite dans son fichier JSON sur le téléphone. */
data class AnalysisRecord(
    val startMs: Long,
    val endMs: Long,
    val level: String,
    val workers: Int,
    val stopped: Boolean,
    val appVersion: String,
    val device: String,
    val androidVersion: Int,
    val cores: Int,
    val folders: List<FolderStat>,
    val samples: List<Sample>,
    /** Photos analysed by type (JPEG, PNG, WebP, Autre). */
    val types: Map<String, Int> = emptyMap(),
    /** Each loading of the models: the first one, and each reload after a pause. */
    val modelLoads: List<ModelLoad> = emptyList(),
    /** The time of each photo stage over the run, in milliseconds (see [StageTimes]). */
    val stages: Map<String, Long> = emptyMap(),
    /** The name of the journal of this run (ChatLab/diagnostics), or empty when there is none. */
    val diagnosticsRun: String = "",
    /** The workers allowed at the start of the run (0 when not kept). */
    val startWorkers: Int = 0,
    /** The memory free at the start of the run, in megabytes (0 when not kept). */
    val memoryAtStartMb: Long = 0L,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
    val analyzed: Int get() = folders.sumOf { it.analyzed }
    val ignored: Int get() = folders.sumOf { it.ignored }

    /** Le résumé affiché dans la liste de l'historique. */
    fun summary(): Summary {
        val cpu = samples.map { it.cpuPercent }
        val seconds = durationMs / 1000.0
        return Summary(
            avgCpu = if (cpu.isEmpty()) 0f else cpu.average().toFloat(),
            peakCpu = cpu.maxOrNull() ?: 0f,
            peakMemMb = samples.maxOfOrNull { it.memMb } ?: 0,
            photosPerSecond = if (seconds <= 0.0) 0.0 else analyzed / seconds,
            batteryStart = samples.firstOrNull()?.batteryPct,
            batteryEnd = samples.lastOrNull()?.batteryPct,
            thermalMax = samples.maxOfOrNull { it.thermal } ?: 0,
        )
    }

    fun toJson(): JSONObject = JSONObject()
        .put("run", JSONObject()
            .put("start", iso(startMs))
            .put("end", iso(endMs))
            .put("durationS", durationMs / 1000)
            .put("level", level)
            .put("workers", workers)
            .put("stopped", stopped))
        .put("types", JSONObject().apply { types.forEach { (k, v) -> put(k, v) } })
        .put("models", JSONArray().apply {
            modelLoads.forEach {
                put(JSONObject()
                    .put("reload", it.reload)
                    .put("t", ((it.atMs - startMs) / 1000).toInt())
                    .put("millis", it.millis)
                    .put("parts", JSONObject().apply { it.parts.forEach { (name, ms) -> put(name, ms) } }))
            }
        })
        .put("stages", JSONObject().apply { stages.forEach { (k, v) -> put(k, v) } })
        .put("diagnosticsRun", diagnosticsRun)
        .put("startWorkers", startWorkers)
        .put("memoryAtStartMb", memoryAtStartMb)
        .put("device", JSONObject()
            .put("model", device)
            .put("android", androidVersion)
            .put("cores", cores)
            .put("appVersion", appVersion))
        .put("folders", JSONArray().apply {
            folders.forEach {
                put(JSONObject()
                    .put("name", it.name)
                    .put("analyzed", it.analyzed)
                    .put("ignored", it.ignored)
                    .put("unread", it.unread)
                    .put("refused", it.refused))
            }
        })
        .put("samples", JSONArray().apply {
            samples.forEach {
                put(JSONObject()
                    .put("t", it.tSec)
                    .put("cpu", it.cpuPercent.toDouble())
                    .put("memMb", it.memMb)
                    .put("battery", it.batteryPct)
                    .put("batteryTempC", it.batteryTempC.toDouble())
                    .put("thermal", it.thermal)
                    .put("workers", it.workers))
            }
        })

    companion object {
        /** Le fichier d'une analyse, relu depuis son JSON. */
        fun fromJson(json: JSONObject): AnalysisRecord {
            val run = json.getJSONObject("run")
            val device = json.getJSONObject("device")
            val folders = json.getJSONArray("folders")
            val samples = json.getJSONArray("samples")
            return AnalysisRecord(
                startMs = parseIso(run.getString("start")),
                endMs = parseIso(run.getString("end")),
                level = run.getString("level"),
                workers = run.getInt("workers"),
                stopped = run.getBoolean("stopped"),
                appVersion = device.getString("appVersion"),
                device = device.getString("model"),
                androidVersion = device.getInt("android"),
                cores = device.getInt("cores"),
                folders = (0 until folders.length()).map { i ->
                    val f = folders.getJSONObject(i)
                    FolderStat(f.getString("name"), f.getInt("analyzed"), f.getInt("ignored"), f.getInt("unread"), f.getInt("refused"))
                },
                samples = (0 until samples.length()).map { i ->
                    val p = samples.getJSONObject(i)
                    Sample(p.getInt("t"), p.getDouble("cpu").toFloat(), p.getInt("memMb"), p.getInt("battery"), p.getDouble("batteryTempC").toFloat(), p.getInt("thermal"), p.optInt("workers", 0))
                },
                stages = json.optJSONObject("stages")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }.orEmpty(),
                diagnosticsRun = json.optString("diagnosticsRun", ""),
                startWorkers = json.optInt("startWorkers", 0),
                memoryAtStartMb = json.optLong("memoryAtStartMb", 0L),
                types = json.optJSONObject("types")?.let { t -> t.keys().asSequence().associateWith { t.getInt(it) } }.orEmpty(),
                modelLoads = json.optJSONArray("models")?.let { m ->
                    val start = parseIso(run.getString("start"))
                    (0 until m.length()).map { i ->
                        val p = m.getJSONObject(i)
                        val parts = p.optJSONObject("parts")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }.orEmpty()
                        ModelLoad(p.getBoolean("reload"), start + p.getInt("t") * 1000L, p.getLong("millis"), parts)
                    }
                }.orEmpty(),
            )
        }

        private fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(ms))

        private fun parseIso(text: String): Long =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.ROOT).apply { timeZone = TimeZone.getDefault() }.parse(text)!!.time
    }
}

data class Summary(
    val avgCpu: Float,
    val peakCpu: Float,
    val peakMemMb: Int,
    val photosPerSecond: Double,
    val batteryStart: Int?,
    val batteryEnd: Int?,
    val thermalMax: Int,
)

object AnalysisFiles {
    /** Le nom du fichier d'une analyse : sa date de début, par exemple « 2026-11-23_1430.json ». */
    fun nameFor(startMs: Long, zone: TimeZone = TimeZone.getDefault()): String =
        SimpleDateFormat("yyyy-MM-dd_HHmm", Locale.ROOT).apply { timeZone = zone }.format(Date(startMs)) + ".json"

    /** Réduit une courbe à [maxPoints] points, en gardant toujours le premier et le dernier. */
    fun downsample(samples: List<Sample>, maxPoints: Int): List<Sample> {
        if (maxPoints <= 0) return emptyList()
        if (samples.size <= maxPoints) return samples
        if (maxPoints == 1) return listOf(samples.first())
        val step = (samples.size - 1).toDouble() / (maxPoints - 1)
        return (0 until maxPoints).map { samples[Math.round(it * step).toInt().coerceIn(0, samples.lastIndex)] }
    }
}
