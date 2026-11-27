package com.example.chatlab

import android.content.Context
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.Palette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Calendar

private val Green = Color(0xFF1F8A4C)
private val Warning = Color(0xFFF5B700)
private val GreyText = Color(0xFF9CA3AF)
private val GreyLine = Color(0xFFD1D5DB)

/** Paramètres, as in the mock-up: the last analysis, the report, the metrics, and erasing what the app created. */
@Composable
fun SettingsPage(
    dataVersion: Int,
    onBack: () -> Unit,
    onWiped: () -> Unit,
) {
    val context = LocalContext.current
    val lastMillis = remember(dataVersion) { ReportStore.lastMillis(context) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(
            modifier = Modifier.padding(top = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("‹", fontSize = 22.sp, color = Palette.Accent, modifier = Modifier.clickable(onClick = onBack))
            Text("Paramètres", fontSize = 28.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        }
        LastAnalysis(lastMillis)
        Divider()
        Block { ReportRow() }
        Divider()
        Block { MetricsRow() }
        Divider()
        WipeSection(dataVersion = dataVersion, onWiped = onWiped)
        Spacer16()
    }
}

/** A line of the page, with its own vertical padding. */
@Composable
private fun Block(content: @Composable () -> Unit) {
    Column(modifier = Modifier.padding(vertical = 14.dp)) { content() }
}

@Composable
private fun Divider() {
    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
}

@Composable
private fun Spacer16() {
    Box(modifier = Modifier.height(16.dp))
}

/** Dernière analyse, with the Détail button that opens its curve. */
@Composable
private fun LastAnalysis(lastMillis: Long?) {
    Block {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("Dernière analyse", fontSize = 15.sp, color = Palette.Ink)
                // The detail view is added with its own change: for now the button is shown, not yet wired.
                PillButton("Détail", enabled = lastMillis != null) {}
            }
            Text(
                lastMillis?.let { formatWhen(it) } ?: "Aucune analyse pour l'instant",
                fontSize = 12.sp,
                color = Palette.Muted,
            )
        }
    }
}

/** "26 novembre 2026, 17 h 48". */
private fun formatWhen(millis: Long): String {
    val c = Calendar.getInstance().apply { timeInMillis = millis }
    val month = Dates.MONTH_NAMES[c.get(Calendar.MONTH)].lowercase()
    return "${c.get(Calendar.DAY_OF_MONTH)} $month ${c.get(Calendar.YEAR)}, " +
        "${c.get(Calendar.HOUR_OF_DAY)} h ${"%02d".format(c.get(Calendar.MINUTE))}"
}

/** Métriques: the folder of the measurements, grey when it is not there or not reachable. */
@Composable
private fun MetricsRow() {
    val context = LocalContext.current
    val available by produceState(initialValue = false) {
        value = withContext(Dispatchers.IO) { OpenFolder.exists("diagnostics") }
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Métriques", fontSize = 15.sp, color = Palette.Ink)
            PillButton("Ouvrir", enabled = available) { OpenFolder.open(context, "diagnostics") }
        }
        Text(
            "Les mesures de l'app, pour comprendre les analyses et les lenteurs.",
            fontSize = 12.sp,
            color = Palette.Muted,
        )
    }
}

/** The steps of erasing: asking for the second press, then erased. */
private enum class WipeStep { IDLE, ASKED, DONE }

/**
 * Tout effacer, in place: a first press asks for a second one, which erases what the app created.
 * Once erased, or when there is nothing to erase, the button is grey. Animated as in the mock-up.
 */
@Composable
private fun WipeSection(dataVersion: Int, onWiped: () -> Unit) {
    val context = LocalContext.current
    var step by remember { mutableStateOf(WipeStep.IDLE) }
    var version by remember { mutableStateOf(0) }
    var taps by remember { mutableStateOf(0) }
    val nothingToErase by produceState(initialValue = false, dataVersion, version) {
        value = withContext(Dispatchers.IO) { !hasCreatedData(context) }
    }
    // While an analysis runs, nothing is erased: it must be stopped first, by the person.
    val running = AnalysisProgress.state.collectAsState().value.running
    val grey = nothingToErase || step == WipeStep.DONE || running
    Column(
        modifier = Modifier.padding(top = 18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WipeButton(
            label = if (step == WipeStep.ASKED && !grey) "Appuyer à nouveau pour confirmer" else "Tout effacer",
            grey = grey,
            taps = taps,
            onClick = {
                if (!running) {
                    taps++
                    if (step == WipeStep.IDLE) {
                        step = WipeStep.ASKED
                    } else {
                        eraseCreatedData(context)
                        Toast.makeText(context, "Tout est effacé.", Toast.LENGTH_SHORT).show()
                        step = WipeStep.DONE
                        version++
                        onWiped()
                    }
                }
            },
        )
        if (running) {
            Text(
                "Une analyse est en cours. Arrêtez-la pour pouvoir tout effacer.",
                fontSize = 13.sp,
                color = Palette.Muted,
            )
        }
        AnimatedVisibility(visible = step == WipeStep.ASKED && !running, exit = fadeOut(tween(300))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Appear(600) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        WarningIcon()
                        Text("Cette opération est irréversible.", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    }
                }
                Appear(1000) { WipeLine("✕", Palette.Danger, "Les données créées par l'application") }
                Appear(1400) { WipeLine("✓", Green, "Vos fichiers originaux restent intacts") }
                Appear(1800) { WipeLine("✓", Green, "Les modèles d'IA") }
            }
        }
        AnimatedVisibility(visible = step == WipeStep.DONE, enter = fadeIn(tween(400))) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Appear(500) { WipeLine("✓", Green, "Données créées par l'application supprimées") }
                Appear(1100) { WipeLine("✓", Green, "Vos fichiers originaux restent intacts") }
            }
        }
    }
}

/** The erase button: red outline, or grey when nothing can be erased. Each press shows a ring. */
@Composable
private fun WipeButton(label: String, grey: Boolean, taps: Int, onClick: () -> Unit) {
    val color by animateColorAsState(if (grey) GreyText else Palette.Danger, tween(400), label = "wipe-text")
    val border by animateColorAsState(if (grey) GreyLine else Palette.Danger, tween(400), label = "wipe-border")
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(Color.White, RoundedCornerShape(12.dp))
            .border(1.dp, border, RoundedCornerShape(12.dp))
            .clickable(enabled = !grey, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
        TapRing(taps)
    }
}

/** The press ring of the mock-up: a red disc that grows and fades from the centre of the button. */
@Composable
private fun TapRing(taps: Int) {
    val progress = remember { Animatable(1f) }
    LaunchedEffect(taps) {
        if (taps > 0) {
            progress.snapTo(0f)
            progress.animateTo(1f, tween(800))
        }
    }
    val p = progress.value
    if (p < 1f) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .graphicsLayer {
                    val scale = if (p < 0.3f) 0.5f + 0.4f * (p / 0.3f) else 0.9f + 0.4f * ((p - 0.3f) / 0.7f)
                    scaleX = scale
                    scaleY = scale
                    alpha = if (p < 0.3f) p / 0.3f else (1f - p) / 0.7f
                }
                .background(Palette.Danger.copy(alpha = 0.18f), CircleShape),
        )
    }
}

/** Fades in and rises a line after a delay, as in the mock-up. */
@Composable
private fun Appear(delayMillis: Long, content: @Composable () -> Unit) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay(delayMillis)
        progress.animateTo(1f, tween(400))
    }
    Box(
        modifier = Modifier.graphicsLayer {
            alpha = progress.value
            translationY = (1f - progress.value) * 4.dp.toPx()
        },
    ) {
        content()
    }
}

/** One line of the erase details: a cross or a tick, and its text. */
@Composable
private fun WipeLine(icon: String, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(icon, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = color, modifier = Modifier.width(18.dp))
        Text(text, fontSize = 14.sp, color = Palette.Ink)
    }
}

/** The yellow warning triangle with its mark, as in the mock-up. */
@Composable
private fun WarningIcon() {
    Canvas(modifier = Modifier.size(18.dp)) {
        val w = size.width
        val h = size.height
        val triangle = Path().apply {
            moveTo(w / 2f, 0f)
            lineTo(w, h)
            lineTo(0f, h)
            close()
        }
        drawPath(triangle, Warning)
        drawRect(Palette.Ink, topLeft = Offset(w * 11f / 24f, h * 9f / 24f), size = Size(w * 2f / 24f, h * 6f / 24f))
        drawRect(Palette.Ink, topLeft = Offset(w * 11f / 24f, h * 16.5f / 24f), size = Size(w * 2f / 24f, h * 2f / 24f))
    }
}

/** True when the app has created something: the analysis record, the recent searches, a report or the folders. */
private fun hasCreatedData(context: Context): Boolean =
    AnalysisRecordFiles.size(context.filesDir) > 0 ||
        RecentStore.read(context).isNotEmpty() ||
        ReportStore.read(context) != null ||
        Sources.read(context).isNotEmpty()

/** Erases what the app created, and nothing else: the photos of the gallery and the models stay. */
private fun eraseCreatedData(context: Context) {
    LibraryStore.wipeAnalysis(context)
    RecentStore.clear(context)
    ReportStore.clear(context)
    AnalysisProgress.clearReport()
    Sources.clear(context)
}
