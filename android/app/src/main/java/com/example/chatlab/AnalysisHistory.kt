package com.example.chatlab

import android.content.Context
import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.RadioRow
import com.example.chatlab.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

private val CurveCpu = Color(0xFF1F3A68)
private val CurveMemory = Color(0xFF3B82C4)
private val CurveBattery = Color(0xFF6B7280)

/** "23 nov. 2026 · 14:30", the date of an analysis as the list shows it. */
fun analysisDateLabel(startMs: Long): String =
    SimpleDateFormat("d MMM yyyy · HH:mm", Locale.FRANCE).format(Date(startMs))

/** A duration in seconds as "2,1 s" (or "850 ms" under a second). */
private fun secondsLabel(millis: Long): String =
    if (millis < 1000) "$millis ms" else "%.1f s".format(Locale.FRANCE, millis / 1000.0)

/** The analysis files, read in the background, with their name. Unreadable files are left out. */
private fun readRecords(context: Context): List<Pair<String, AnalysisRecord>> =
    RunArchive.list(context).mapNotNull { file ->
        try {
            file.name to AnalysisRecord.fromJson(JSONObject(file.readText()))
        } catch (e: Exception) {
            null
        }
    }

private fun readRecord(context: Context, name: String): AnalysisRecord? = try {
    AnalysisRecord.fromJson(JSONObject(File(RunArchive.directory(context), name).readText()))
} catch (e: Exception) {
    null
}

/** The list of the analyses, the most recent first. Each card opens its detail. */
@Composable
fun AnalysisHistoryPage(onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    val records by produceState<List<Pair<String, AnalysisRecord>>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { readRecords(context) }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Historique des analyses", onBack = onBack)
        val list = records
        when {
            list == null -> Text("Lecture des analyses…", fontSize = 14.sp, color = Palette.Muted)
            list.isEmpty() -> Text(
                "Aucune analyse enregistrée pour l'instant. Chaque analyse terminée apparaît ici, avec ses courbes.",
                fontSize = 14.sp,
                color = Palette.Muted,
            )
            else -> list.forEach { (name, record) -> RunCard(record) { onOpen(name) } }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RunCard(record: AnalysisRecord, onClick: () -> Unit) {
    val summary = record.summary()
    val shape = RoundedCornerShape(12.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, Palette.Line, shape)
            .clickable(onClick = onClick)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            analysisDateLabel(record.startMs) + if (record.stopped) " · arrêtée" else "",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
        Text(
            "${Formats.photos(record.analyzed)} · ${durationText((record.durationMs / 60_000).toInt())}",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        Text(
            "CPU moyen ${summary.avgCpu.roundToInt()} % · mémoire max ${summary.peakMemMb} Mo",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        Text("Détail et courbes ›", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
    }
}

private sealed interface Loaded {
    data object Loading : Loaded
    data object Missing : Loaded
    class Ready(val record: AnalysisRecord) : Loaded
}

/** The content of one analysis file, in blocks: the run, its curves, the types, the folders, the models. */
@Composable
fun AnalysisDetailPage(name: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val loaded by produceState<Loaded>(initialValue = Loaded.Loading, name) {
        value = withContext(Dispatchers.IO) {
            readRecord(context, name)?.let { Loaded.Ready(it) } ?: Loaded.Missing
        }
    }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        val state = loaded
        PageHeader(if (state is Loaded.Ready) analysisDateLabel(state.record.startMs) else "Analyse", onBack = onBack)
        when (state) {
            Loaded.Loading -> Text("Lecture du fichier…", fontSize = 14.sp, color = Palette.Muted)
            Loaded.Missing -> Text("Ce fichier d'analyse n'est plus là ou ne peut pas être lu.", fontSize = 14.sp, color = Palette.Muted)
            is Loaded.Ready -> {
                val record = state.record
                RunBlock(record)
                Block("Courbes") {
                    CurvesChart(record)
                    CurvesLegend()
                }
                Block("Résumé") { SummaryRows(record) }
                Block("Types de photos") {
                    val types = record.types
                    if (types.isEmpty()) Text("Aucune photo analysée.", fontSize = 14.sp, color = Palette.Muted)
                    types.forEach { (type, count) -> KeyValue(type, count.toString()) }
                }
                Block("Dossiers") {
                    record.folders.forEach { folder ->
                        Text(folder.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                        Text(
                            "${folder.analyzed} analysées · ${folder.ignored} ignorées · ${folder.unread} non lues · ${folder.refused} refusées",
                            fontSize = 13.sp,
                            color = Palette.Muted,
                        )
                    }
                }
                Block("Chargements des modèles") {
                    if (record.modelLoads.isEmpty()) Text("Aucun chargement.", fontSize = 14.sp, color = Palette.Muted)
                    record.modelLoads.forEach { load ->
                        KeyValue(
                            if (load.reload) "Reprise après la pause" else "Premier chargement",
                            "${ChartMath.clockLabel(record.startMs, ((load.atMs - record.startMs) / 1000).toInt())} · ${secondsLabel(load.millis)}",
                        )
                        load.parts.forEach { (name, millis) -> KeyValue("   $name", secondsLabel(millis)) }
                    }
                }
                Block("Téléphone") {
                    KeyValue("Appareil", record.device)
                    KeyValue("Android", record.androidVersion.toString())
                    KeyValue("Cœurs", record.cores.toString())
                    KeyValue("Version de l'appli", record.appVersion)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RunBlock(record: AnalysisRecord) {
    Block("Ce qui s'est passé") {
        KeyValue("Début", analysisDateLabel(record.startMs))
        KeyValue("Durée", durationText((record.durationMs / 60_000).toInt()))
        KeyValue("Fin", if (record.stopped) "arrêtée par l'utilisateur" else "terminée")
        KeyValue("Niveau", "${AnalysisLevel.fromKey(record.level).label} · ${record.workers} à la fois")
        KeyValue("Photos analysées", record.analyzed.toString())
        KeyValue("Photos ignorées", record.ignored.toString())
    }
}

@Composable
private fun SummaryRows(record: AnalysisRecord) {
    val s = record.summary()
    KeyValue("CPU moyen", "${s.avgCpu.roundToInt()} %")
    KeyValue("CPU au pic", "${s.peakCpu.roundToInt()} %")
    KeyValue("Mémoire au pic", "${s.peakMemMb} Mo")
    KeyValue("Photos par seconde", "%.2f".format(Locale.FRANCE, s.photosPerSecond))
    KeyValue(
        "Batterie",
        if (s.batteryStart != null && s.batteryEnd != null) "${s.batteryStart} % → ${s.batteryEnd} %" else "–",
    )
    KeyValue("Pic thermique", "niveau ${s.thermalMax}")
}

@Composable
private fun Block(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(title)
        content()
    }
}

@Composable
private fun KeyValue(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.Top) {
        Text(label, fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 14.sp, color = Palette.Ink)
    }
}

/**
 * The three curves of an analysis, solid lines. Left axis: percent (CPU and battery). Right axis:
 * memory in megabytes. Bottom: the clock every half hour.
 */
@Composable
private fun CurvesChart(record: AnalysisRecord) {
    val duration = (record.durationMs / 1000).toInt()
    val memoryMax = ChartMath.memoryAxisMax(record.samples.maxOfOrNull { it.memMb } ?: 0)
    val labelColor = Palette.Muted.toArgb()
    Canvas(Modifier.fillMaxWidth().height(220.dp)) {
        val left = 36.dp.toPx()
        val right = 44.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val width = size.width - left - right
        val height = size.height - top - bottom
        val text = Paint().apply {
            color = labelColor
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val native = drawContext.canvas.nativeCanvas

        for (fraction in listOf(0f, 0.25f, 0.5f, 0.75f, 1f)) {
            val y = top + height * (1 - fraction)
            drawLine(Palette.Line, Offset(left, y), Offset(left + width, y), strokeWidth = 1.dp.toPx())
            native.drawText("${(fraction * 100).toInt()} %", 0f, y + 4.dp.toPx(), text)
            native.drawText("${(memoryMax * fraction).toInt()} Mo", left + width + 6.dp.toPx(), y + 4.dp.toPx(), text)
        }
        text.textAlign = Paint.Align.CENTER
        for (mark in ChartMath.halfHourMarks(duration)) {
            val x = left + ChartMath.xFraction(mark, duration) * width
            drawLine(Palette.Line, Offset(x, top), Offset(x, top + height), strokeWidth = 1.dp.toPx())
            native.drawText(ChartMath.clockLabel(record.startMs, mark), x, top + height + 16.dp.toPx(), text)
        }

        fun curve(value: (Sample) -> Float, max: Float): Path = Path().apply {
            record.samples.forEachIndexed { index, sample ->
                val x = left + ChartMath.xFraction(sample.tSec, duration) * width
                val y = top + height * (1 - ChartMath.yFraction(value(sample), max))
                if (index == 0) moveTo(x, y) else lineTo(x, y)
            }
        }
        val stroke = Stroke(width = 2.dp.toPx())
        drawPath(curve({ it.batteryPct.toFloat() }, 100f), CurveBattery, style = stroke)
        drawPath(curve({ it.memMb.toFloat() }, memoryMax.toFloat()), CurveMemory, style = stroke)
        drawPath(curve({ it.cpuPercent }, 100f), CurveCpu, style = stroke)
    }
}

@Composable
private fun CurvesLegend() {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendItem(CurveCpu, "CPU (%)")
        LegendItem(CurveMemory, "Mémoire (Mo, à droite)")
        LegendItem(CurveBattery, "Batterie (%)")
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.width(14.dp).height(3.dp)) {
            drawLine(color, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height)
        }
        Spacer(Modifier.width(4.dp))
        Text(label, fontSize = 11.sp, color = Palette.Muted)
    }
}

/** The level of the analyses, chosen once; it applies from the next analysis. */
@Composable
fun LevelPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(LevelStore.read(context)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Niveau d'analyse", onBack = onBack)
        Text(
            "Un niveau plus haut analyse plusieurs photos à la fois : c'est plus rapide, mais le téléphone chauffe et consomme davantage. Le choix prend effet à la prochaine analyse.",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        AnalysisLevel.entries.forEach { level ->
            RadioRow(
                label = level.label,
                count = "${level.workers} à la fois",
                selected = level == chosen,
                onClick = {
                    chosen = level
                    LevelStore.write(context, level)
                },
            )
        }
    }
}
