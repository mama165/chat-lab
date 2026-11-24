package com.example.chatlab

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Paint
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.RadioRow
import com.example.chatlab.ui.SectionLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
internal fun secondsLabel(millis: Long): String =
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

/** The text of an analysis file, exactly as it is saved, or null if it cannot be read. */
private fun readText(context: Context, name: String): String? = try {
    File(RunArchive.directory(context), name).readText()
} catch (e: Exception) {
    null
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
        Text(
            "Chaque analyse terminée est gardée dans ChatLab/analyses sur le téléphone.",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
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
        Text(
            "Pour le détail d'une analyse, voir la fiche technique.",
            fontSize = 12.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        )
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun RunCard(record: AnalysisRecord, onClick: () -> Unit) {
    val summary = record.summary()
    val minutes = (record.durationMs / 60_000).toInt()
    val seconds = record.durationMs / 1000.0
    Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 14.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                analysisDateLabel(record.startMs) + if (record.stopped) " · arrêtée" else "",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
            )
            Text(
                "${AnalysisLevel.fromKey(record.level).label} · ${HistoryText.shortDuration(minutes)}",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Accent,
            )
        }
        Spacer(Modifier.height(8.dp))
        StatLine("CPU", "${summary.avgCpu.roundToInt()} % en moyenne")
        StatLine("Mémoire", "${summary.peakMemMb} Mo au pic")
        StatLine(
            "Batterie",
            if (summary.batteryStart != null && summary.batteryEnd != null) "${summary.batteryStart} % → ${summary.batteryEnd} %" else "–",
        )
        StatLine("Chauffe", HistoryText.heating(summary.thermalMax))
        StatLine("Photos", HistoryText.photosInFolders(record.analyzed, record.folders.size))
        StatLine("Vitesse", HistoryText.photosPerSecond(HistoryText.speed(record.analyzed, seconds)))
        Spacer(Modifier.height(10.dp))
        val pill = RoundedCornerShape(20.dp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(pill)
                .border(1.dp, Palette.Accent, pill)
                .clickable(onClick = onClick)
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("Détail et courbes ›", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

/** One line of an analysis card: a label on the left, its value on the right. */
@Composable
private fun StatLine(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.weight(1f))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, textAlign = TextAlign.End)
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Field))
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
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // A plain text here: a section label would take the whole width and push the button away.
                    Text("Courbes pendant l'analyse", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
                    CopyDataButton(name)
                }
                CurvesLegend(workersUsed = AnalysisTechnical.workersUsed(record))
                CurvesChart(record)
                Block("Contexte") {
                    AnalysisTechnical.contextRows(record).forEach { (label, value) -> KeyValue(label, value) }
                }
                Block("Technique") {
                    AnalysisTechnical.technicalRows(record).forEach { (label, value) -> KeyValue(label, value) }
                }
                Block("Métier") {
                    record.folders.forEach { folder ->
                        KeyValue(folder.name, "${folder.analyzed} analysées · ${folder.ignored} ignorées · ${folder.unread} non lues · ${folder.refused} refusées")
                    }
                    record.types.forEach { (type, count) -> KeyValue(type, count.toString()) }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

private enum class CopyPhase { IDLE, COPYING, COPIED, FAILED }

/**
 * "Copier les données": the analysis file, as saved, goes to the clipboard so that it can be
 * shared. While it is copied the button says so; then it confirms, and returns to its usual state.
 */
@Composable
private fun CopyDataButton(name: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var phase by remember { mutableStateOf(CopyPhase.IDLE) }
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .clip(shape)
            .border(1.dp, Palette.Accent, shape)
            .clickable(enabled = phase == CopyPhase.IDLE) {
                phase = CopyPhase.COPYING
                scope.launch {
                    val text = withContext(Dispatchers.IO) { readText(context, name) }
                    phase = if (text == null) {
                        CopyPhase.FAILED
                    } else {
                        context.getSystemService(ClipboardManager::class.java)
                            .setPrimaryClip(ClipData.newPlainText("Analyse ChatLab", text))
                        CopyPhase.COPIED
                    }
                    delay(2_000)
                    phase = CopyPhase.IDLE
                }
            }
            .padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (phase == CopyPhase.COPYING) {
            GaugeRing(fraction = null, size = 12.dp)
        }
        Text(
            when (phase) {
                CopyPhase.IDLE -> "Copier les données"
                CopyPhase.COPYING -> "Copie en cours…"
                CopyPhase.COPIED -> "Copié dans le presse-papier"
                CopyPhase.FAILED -> "Copie impossible"
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Accent,
        )
    }
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
/** The colour of a number of workers: more workers, a more intense colour. */
private fun workerColor(workers: Int): Color = when (workers) {
    4 -> Color(0xFFB3261E)
    3 -> Color(0xFFE0622B)
    2 -> Color(0xFFF5A25D)
    else -> Color(0xFFFBD3B0)
}

@Composable
private fun CurvesChart(record: AnalysisRecord) {
    val duration = (record.durationMs / 1000).toInt()
    val memoryMax = ChartMath.memoryAxisMax(record.samples.maxOfOrNull { it.memMb } ?: 0)
    val labelColor = Palette.Muted.toArgb()
    val changes = remember(record) { ChartMath.workerChanges(record.samples) }
    // The changes whose dashed line is up, by their index in the list. A tap on a point toggles it.
    var opened by remember(record) { mutableStateOf(setOf<Int>()) }
    val lineLevels = changes.indices.map { index ->
        animateFloatAsState(if (index in opened) 1f else 0f, animationSpec = tween(400), label = "worker$index").value
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(220.dp)
            .pointerInput(changes) {
                detectTapGestures { tap ->
                    val left = 36.dp.toPx()
                    val right = 44.dp.toPx()
                    val bottom = 22.dp.toPx()
                    val width = size.width - left - right
                    val baseline = size.height - bottom
                    val radius = 12.dp.toPx()
                    val nearest = changes.indices.minByOrNull { index ->
                        val x = left + ChartMath.xFraction(changes[index].tSec, duration) * width
                        (Offset(x, baseline) - tap).getDistance()
                    } ?: return@detectTapGestures
                    val x = left + ChartMath.xFraction(changes[nearest].tSec, duration) * width
                    if ((Offset(x, baseline) - tap).getDistance() <= radius) {
                        opened = if (nearest in opened) opened - nearest else opened + nearest
                    }
                }
            },
    ) {
        val left = 36.dp.toPx()
        val right = 44.dp.toPx()
        val top = 8.dp.toPx()
        val bottom = 22.dp.toPx()
        val width = size.width - left - right
        val height = size.height - top - bottom
        val baseline = top + height
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

        // One small point on the time axis per change of workers; a tap lifts a dashed line from it.
        changes.forEachIndexed { index, change ->
            val x = left + ChartMath.xFraction(change.tSec, duration) * width
            val color = workerColor(change.workers)
            val level = lineLevels[index]
            if (level > 0f) {
                drawLine(
                    color,
                    Offset(x, baseline),
                    Offset(x, baseline - height * level),
                    strokeWidth = 1.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f),
                )
            }
            drawCircle(color, radius = 3.dp.toPx(), center = Offset(x, baseline))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CurvesLegend(workersUsed: Int) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LegendItem(CurveCpu, "CPU (%)")
        LegendItem(CurveMemory, "Mémoire (Mo, à droite)")
        LegendItem(CurveBattery, "Batterie (%)")
        WorkerLegend(workersUsed)
    }
}

/** Only the numbers of workers the analysis used, from the most to 1: 2 gives "2 1", 4 gives "4 3 2 1". */
@Composable
private fun WorkerLegend(workersUsed: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Workers :", fontSize = 11.sp, color = Palette.Muted)
        (workersUsed.coerceAtLeast(1) downTo 1).forEach { workers ->
            Canvas(Modifier.size(8.dp)) { drawCircle(workerColor(workers)) }
            Text("$workers", fontSize = 11.sp, color = Palette.Muted)
        }
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
        PageHeader("Performance", onBack = onBack)
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
