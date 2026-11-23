package com.example.chatlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.Canvas
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chatlab.ui.OutlineButton
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import com.example.chatlab.ui.SectionLabel
import com.example.chatlab.ui.StopSquare
import com.example.chatlab.ui.TabTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Photos tab: the folders to analyse, the report of the last analysis, then the progress. */
@Composable
fun PhotosScreen(
    entries: List<LibraryEntry>,
    onSettings: () -> Unit,
    onAddFolders: () -> Unit,
    onHistory: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val progress by AnalysisProgress.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val pickers = rememberFolderPickers(onAddSource = onAddFolders) { version++ }
    val chatlab = remember(version) { folderName(context, KEY_FOLDER) }
    val sources = remember(version) { Sources.read(context) }
    val lastRun = remember(progress.running, version, entries) { ReportStore.readRun(context) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(progress.running) {
        while (progress.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startFullAnalysis(context)
    }
    val start = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startFullAnalysis(context)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(0.dp))
        TabTitle(
            "Photos",
            onSettings = onSettings,
            // The plus adds a folder, as in the mockup; not before the chatlab folder and a first folder.
            onAdd = if (chatlab != null && sources.isNotEmpty()) onAddFolders else null,
        )
        when {
            chatlab == null || sources.isEmpty() -> FirstSteps(chatlab, sources, pickers)
            progress.running -> Running(progress, now, sources)
            else -> Idle(
                sources = sources,
                lastRun = lastRun,
                message = progress.message,
                onRemove = { uri ->
                    Sources.remove(context, uri)
                    version++
                },
                onStart = start,
                onHistory = onHistory,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun startFullAnalysis(context: Context) {
    val chatlab = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    if (chatlab != null && Sources.read(context).isNotEmpty()) AnalysisService.start(context, chatlab)
}

/** First launch: choose the chatlab folder, then at least one folder of photos. */
@Composable
private fun FirstSteps(chatlab: String?, sources: List<SourceFolder>, pickers: FolderPickers) {
    Text("Pour commencer, choisissez le dossier chatlab, puis les dossiers de photos.", fontSize = 15.sp, color = Palette.Ink)
    Step(1, "Le dossier chatlab", "Il contient le sous-dossier models. Le carnet y est enregistré.", chatlab, "Choisir", pickers.chooseChatlab)
    Step(
        2,
        "Les dossiers de photos",
        "Sous-dossiers compris. Les photos restent à leur place. Vous pouvez en ajouter plusieurs.",
        sources.takeIf { it.isNotEmpty() }?.joinToString(", ") { it.name },
        "Ajouter un dossier",
        pickers.addSource,
    )
}

@Composable
private fun Step(number: Int, title: String, detail: String, chosen: String?, action: String, onChoose: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("$number · $title", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        Text(detail, fontSize = 13.sp, color = Palette.Muted)
        if (chosen != null) Text("Choisi : $chosen ✓", fontSize = 14.sp, color = Palette.Accent)
        if (chosen == null || number == 2) PrimaryButton(if (chosen == null) action else "Ajouter un autre dossier", onClick = onChoose)
    }
}

/** At rest (view 1.5): the names of the folders, the performance, and the button to start. */
@Composable
private fun Idle(
    sources: List<SourceFolder>,
    lastRun: RunReport?,
    message: String,
    onRemove: (String) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
) {
    if (lastRun != null) RunBanner(lastRun, onHistory)
    SectionLabel("Dossiers")
    sources.forEach { folder -> FolderNameRow(folder.name, onRemove = { onRemove(folder.uri) }) }
    PerformanceChoice()
    PrimaryButton("Lancer l'analyse", onClick = onStart, enabled = sources.isNotEmpty())
    if (message.isNotBlank()) {
        Text(message, fontSize = 13.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** A folder of the list, by its name only, with the cross that removes it (view 1.5). */
@Composable
private fun FolderNameRow(name: String, onRemove: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The folder icon in a light square, as in the mockup.
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Soft),
            contentAlignment = Alignment.Center,
        ) {
            Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
        }
        Text(name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
        Text("✕", fontSize = 16.sp, color = Palette.Muted, modifier = Modifier.clickable(onClick = onRemove).padding(4.dp))
    }
}

/**
 * The performance, chosen before the analysis (view 1.5): four choices, the chosen one in blue with
 * its check, and under them how many photos are read at once.
 */
@Composable
private fun PerformanceChoice() {
    val context = LocalContext.current
    var chosen by remember { mutableStateOf(LevelStore.read(context)) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Performance")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            AnalysisLevel.entries.forEach { level ->
                val selected = level == chosen
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            chosen = level
                            LevelStore.write(context, level)
                        },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(modifier = Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        if (selected) {
                            CheckMark(16.dp)
                        } else {
                            Box(modifier = Modifier.size(16.dp).border(1.dp, Color(0xFFD0D5DD), CircleShape))
                        }
                    }
                    Text(
                        level.label,
                        fontSize = 13.sp,
                        color = if (selected) Palette.Accent else Palette.Ink,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
        Text(chosen.photosAtOnce(), fontSize = 12.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Une coche dans un rond clair : le dossier est fini ou n'a rien de nouveau. */
@Composable
internal fun CheckMark(dimension: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(dimension)) {
        drawCircle(Palette.Soft, radius = size.minDimension / 2)
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.8.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val tick = Path().apply {
            moveTo(w * 0.28f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.68f)
            lineTo(w * 0.72f, h * 0.36f)
        }
        drawPath(tick, Palette.Accent, style = stroke)
    }
}

/** Un sablier gris : le dossier attend son tour. */
@Composable
internal fun Hourglass(dimension: androidx.compose.ui.unit.Dp = 20.dp) {
    Canvas(modifier = Modifier.size(dimension)) {
        val w = size.width
        val h = size.height
        val stroke = Stroke(width = 1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
        val glass = Path().apply {
            moveTo(w * 0.22f, h * 0.10f); lineTo(w * 0.78f, h * 0.10f)
            moveTo(w * 0.22f, h * 0.90f); lineTo(w * 0.78f, h * 0.90f)
            moveTo(w * 0.30f, h * 0.10f); lineTo(w * 0.30f, h * 0.28f); lineTo(w * 0.50f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.72f); lineTo(w * 0.30f, h * 0.90f)
            moveTo(w * 0.70f, h * 0.10f); lineTo(w * 0.70f, h * 0.28f); lineTo(w * 0.50f, h * 0.50f)
            lineTo(w * 0.70f, h * 0.72f); lineTo(w * 0.70f, h * 0.90f)
        }
        drawPath(glass, Palette.Muted, style = stroke)
    }
}

/**
 * Une jauge circulaire : une part de cercle qui suit l'avancement, ou un tour continu si
 * l'avancement n'est pas connu (null).
 */
@Composable
internal fun GaugeRing(fraction: Float?, size: androidx.compose.ui.unit.Dp = 20.dp) {
    if (fraction == null) {
        CircularProgressIndicator(modifier = Modifier.size(size), color = Palette.Accent, strokeWidth = 2.dp)
    } else {
        Canvas(modifier = Modifier.size(size)) {
            val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
            val inset = 1.dp.toPx()
            val arc = Size(this.size.width - 2 * inset, this.size.height - 2 * inset)
            drawArc(Palette.Field, 0f, 360f, false, Offset(inset, inset), arc, style = stroke)
            drawArc(Palette.Accent, -90f, 360f * fraction, false, Offset(inset, inset), arc, style = stroke)
        }
    }
}

/** L'encart de bilan : analyse terminée, photos analysées, fichiers non lus. */
@Composable
private fun RunBanner(run: RunReport, onHistory: () -> Unit) {
    val clock = Dates.formatTaken(run.finishedAt, java.util.TimeZone.getDefault()).substringAfter(' ')
    val day = Dates.short(SimpleDate.of(run.finishedAt))
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(Palette.Field)
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .semantics { contentDescription = "Bilan de la dernière analyse" },
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            if (run.stopped) "Analyse arrêtée · $day, $clock" else "Analyse terminée · $day, $clock",
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
        BannerLine("✓", GREEN, "${run.analyzed}", if (run.analyzed > 1) "photos analysées" else "photo analysée")
        if (run.unread > 0) BannerLine("!", Palette.Danger, "${run.unread}", if (run.unread > 1) "fichiers non lus" else "fichier non lu")
        if (run.refusedFolders > 0) {
            BannerLine("!", Palette.Danger, "${run.refusedFolders}", if (run.refusedFolders > 1) "dossiers refusés par Android" else "dossier refusé par Android")
        }
        Text(
            "Voir l'historique des analyses ›",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Accent,
            modifier = Modifier.clickable(onClick = onHistory).padding(top = 4.dp),
        )
    }
}

private val GREEN = Color(0xFF1F7A4D)

@Composable
private fun BannerLine(icon: String, color: Color, number: String, label: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(icon, fontSize = 13.sp, color = color, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
        Text("$number $label", fontSize = 13.sp, color = Palette.Ink)
    }
}

/**
 * During the analysis: the running list (view 2.1), the pause (view 2.2), or the reprise after a
 * pause (view 2.3). The "Plus de détails" control is at the top, and only when running.
 */
@Composable
private fun Running(running: Progress, now: Long, sources: List<SourceFolder>) {
    // A folder added while the analysis runs is shown as waiting: the analysis does not read it.
    val progress = running.copy(
        folders = running.folders + sources
            .filter { source -> running.folders.none { it.name == source.name } }
            .map { FolderProgress(it.name, done = 0, total = 0, later = true) },
    )
    var reprise by remember { mutableStateOf(false) }
    var showDetails by remember { mutableStateOf(false) }
    LaunchedEffect(progress.loadingModels > 0) {
        if (progress.loadingModels > 0) reprise = true
    }
    if (!progress.paused) DetailsToggle(open = showDetails, onToggle = { showDetails = !showDetails })
    when {
        progress.paused -> PausedScreen(progress)
        reprise -> RepriseScreen(progress, now, showDetails)
        else -> RunningDetail(progress, now, showDetails)
    }
}

/** "Plus de détails ›", or "Moins de détails" once they are open. Blue text, no background. */
@Composable
private fun DetailsToggle(open: Boolean, onToggle: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Text(
            if (open) "Moins de détails" else "Plus de détails ›",
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Accent,
            modifier = Modifier.clickable(onClick = onToggle).padding(vertical = 4.dp),
        )
    }
}

/** A small clock in the accent colour, for the time left. */
@Composable
private fun ClockMark(dimension: androidx.compose.ui.unit.Dp) {
    Canvas(modifier = Modifier.size(dimension)) {
        val line = 1.8.dp.toPx()
        val centre = Offset(size.width / 2, size.height / 2)
        drawCircle(Palette.Accent, radius = size.minDimension / 2 - line / 2, center = centre, style = Stroke(width = line))
        drawLine(Palette.Accent, centre, Offset(centre.x, size.height * 0.27f), strokeWidth = line, cap = StrokeCap.Round)
        drawLine(Palette.Accent, centre, Offset(size.width * 0.72f, size.height * 0.6f), strokeWidth = line, cap = StrokeCap.Round)
    }
}

/** The ring of the progress: the blue part only, the percentage in its middle, the time left under it. */
@Composable
private fun ProgressRing(progress: Progress, now: Long) {
    Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(150.dp)) {
            val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
            val inset = 6.dp.toPx()
            val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
            drawArc(Palette.Accent, -90f, 360f * progress.fraction, false, Offset(inset, inset), arcSize, style = stroke)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${(progress.fraction * 100).toInt()} %", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ClockMark(13.dp)
                Text(remainingText(progress.remaining(now)), fontSize = 12.sp, color = Palette.Muted)
            }
        }
    }
}

/** The running list (view 2.1): the ring, the performance, the folders with their marks. */
@Composable
private fun RunningDetail(progress: Progress, now: Long, showDetails: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        ProgressRing(progress, now)
        AnimatedVisibility(
            visible = showDetails,
            enter = fadeIn(tween(250)) + expandVertically(tween(250)),
            exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
        ) {
            Text(countLine(progress), fontSize = 16.sp, color = Palette.Ink)
        }
        if (progress.levelLabel.isNotBlank()) {
            Text("Performance : ${progress.levelLabel}", fontSize = 12.sp, color = Palette.Accent)
        }
        if (progress.message == "Arrêt en cours…") {
            Text("Arrêt en cours, ce qui est fait est enregistré…", fontSize = 12.sp, color = Palette.Muted, textAlign = TextAlign.Center)
        }
    }
    if (progress.folders.isNotEmpty()) {
        SectionLabel("Par dossier")
        progress.folders.forEach { FolderProgressRow(it, showDetails) }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (progress.paused) {
            PrimaryButton("▶ Reprendre", onClick = { AnalysisService.resume() }, modifier = Modifier.weight(1f))
        } else {
            PrimaryButton("❚❚ Pause", onClick = { AnalysisService.pause() }, modifier = Modifier.weight(1f))
        }
        OutlineButton("Arrêter", onClick = { AnalysisService.stop() }, modifier = Modifier.weight(1f), leading = { StopSquare() })
    }
    Text(
        "Une jauge qui tourne : le dossier est en cours. Un sablier : il attend son tour. Une coche : il est fini ou il n'est pas relu.",
        fontSize = 12.sp,
        color = Palette.Muted,
    )
}

/** What the count line says: photos done out of the total, or why there is nothing to read. */
private fun countLine(progress: Progress): String = when {
    progress.total > 0 -> "${progress.done} sur ${progress.total} nouvelles photos"
    progress.message == "Rien de nouveau" -> "Rien de nouveau à analyser"
    else -> progress.message.ifBlank { "Préparation…" }
}

/** Pause (view 2.2): the ring frozen in grey, what is done, Reprendre and Arrêter. */
@Composable
private fun PausedScreen(progress: Progress) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(150.dp)) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = Offset(inset, inset)
                drawArc(Palette.Field, 0f, 360f, false, topLeft, arcSize, style = stroke)
                drawArc(Color(0xFF9AA3AF), -90f, 360f * progress.fraction, false, topLeft, arcSize, style = stroke)
            }
            Text("En pause", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        }
        Text("${progress.done} sur ${progress.total} photos", fontSize = 16.sp, color = Palette.Ink)
        Text(
            "Rien ne tourne · ${Formats.photos(progress.done)} déjà faite${if (progress.done > 1) "s" else ""}, rien n'est perdu",
            fontSize = 13.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PrimaryButton("▶ Reprendre", onClick = { AnalysisService.resume() }, modifier = Modifier.weight(1f))
        OutlineButton("Arrêter", onClick = { AnalysisService.stop() }, modifier = Modifier.weight(1f), leading = { StopSquare() })
    }
}

/** The steps of the reprise sequence (view 2.3), in order. */
private const val STEP_LOADING = 0
private const val STEP_READY = 1
private const val STEP_FOLDING = 2
private const val STEP_CALC = 3
private const val STEP_REMAINING = 4
private const val STEP_FOLDERS = 5
private const val STEP_BUTTONS = 6

/** What the line under the figure says during the sequence. */
private enum class Subline { LOADING, SUMMARY, CALC, REMAINING }

/**
 * Reprise (view 2.3). While the models reload: a turning ring and the three model rows. Once they
 * are ready: the summary of the reload, the rows fold away, the remaining time is calculated, then
 * the folders come back one by one and the buttons appear. The timing is cosmetic; the models and
 * the photos are real.
 */
@Composable
private fun RepriseScreen(progress: Progress, now: Long, showDetails: Boolean) {
    var step by remember { mutableIntStateOf(STEP_LOADING) }
    var foldersShown by remember { mutableIntStateOf(0) }
    LaunchedEffect(progress.reloadDone) {
        if (!progress.reloadDone) {
            step = STEP_LOADING
            foldersShown = 0
            return@LaunchedEffect
        }
        step = STEP_READY
        delay(1200)
        step = STEP_FOLDING
        delay(1000)
        step = STEP_CALC
        delay(1300)
        step = STEP_REMAINING
        delay(800)
        step = STEP_FOLDERS
        for (i in 1..progress.folders.size) {
            foldersShown = i
            delay(180)
        }
        step = STEP_BUTTONS
    }
    val loading = step == STEP_LOADING
    val subline = when (step) {
        STEP_LOADING -> Subline.LOADING
        STEP_READY, STEP_FOLDING -> Subline.SUMMARY
        STEP_CALC -> Subline.CALC
        else -> Subline.REMAINING
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(130.dp), contentAlignment = Alignment.Center) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(130.dp), color = Palette.Accent, strokeWidth = 10.dp)
            } else {
                Canvas(modifier = Modifier.size(130.dp)) {
                    val stroke = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round)
                    val inset = 5.dp.toPx()
                    val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                    drawArc(Palette.Accent, -90f, 360f * progress.fraction, false, Offset(inset, inset), arcSize, style = stroke)
                }
                Text("${(progress.fraction * 100).toInt()} %", fontSize = 24.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            }
        }
        if (loading) {
            Text("Chargement des modèles", fontSize = 16.sp, color = Palette.Ink)
        } else {
            AnimatedVisibility(
                visible = showDetails,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
            ) {
                Text("${progress.done} sur ${progress.total} nouvelles photos", fontSize = 16.sp, color = Palette.Ink)
            }
        }
        Crossfade(targetState = subline, label = "subline") { line ->
            when (line) {
                Subline.LOADING -> Text(
                    "Les photos reprendront une fois les modèles prêts.",
                    fontSize = 13.sp,
                    color = Palette.Muted,
                    textAlign = TextAlign.Center,
                )
                Subline.SUMMARY -> Text(reloadSummary(progress.modelSteps), fontSize = 13.sp, color = Palette.Muted)
                Subline.CALC -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Hourglass(14.dp)
                    Spacer(Modifier.width(6.dp))
                    Text("Calcul du temps restant", fontSize = 13.sp, color = Palette.Accent)
                }
                Subline.REMAINING -> Text(remainingText(progress.remaining(now)), fontSize = 13.sp, color = Palette.Muted)
            }
        }
    }
    AnimatedVisibility(
        visible = step < STEP_FOLDING,
        exit = shrinkVertically(tween(900)) + fadeOut(tween(900)),
    ) {
        ModelRows(progress)
    }
    AnimatedVisibility(visible = step >= STEP_FOLDERS, enter = fadeIn(tween(300))) {
        SectionLabel("Par dossier")
    }
    progress.folders.forEachIndexed { index, folder ->
        AnimatedVisibility(
            visible = foldersShown > index,
            enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 4 },
        ) {
            FolderProgressRow(folder, showDetails)
        }
    }
    AnimatedVisibility(visible = step >= STEP_BUTTONS, enter = fadeIn(tween(400))) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PrimaryButton("❚❚ Pause", onClick = { AnalysisService.pause() }, modifier = Modifier.weight(1f))
            OutlineButton("Arrêter", onClick = { AnalysisService.stop() }, modifier = Modifier.weight(1f), leading = { StopSquare() })
        }
    }
}

/** The three models, each waiting, loading (turning) or done with its real loading time. */
@Composable
private fun ModelRows(progress: Progress) {
    val loading = progress.loadingModels > 0
    Column(modifier = Modifier.fillMaxWidth()) {
        reloadRows(progress.modelSteps, loading).forEachIndexed { index, (name, state) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(modifier = Modifier.size(22.dp), contentAlignment = Alignment.Center) {
                    when (state) {
                        ModelRowState.DONE -> CheckMark(22.dp)
                        ModelRowState.LOADING -> GaugeRing(null, 22.dp)
                        ModelRowState.WAITING -> Hourglass(22.dp)
                    }
                }
                Text(name, fontSize = 15.sp, color = Palette.Ink, modifier = Modifier.weight(1f))
                if (state == ModelRowState.DONE) {
                    Text(secondsLabel(progress.modelSteps.getValue(name)), fontSize = 13.sp, color = Palette.Muted)
                }
            }
            if (index < 2) Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        }
    }
}

@Composable
private fun FolderProgressRow(folder: FolderProgress, showDetails: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // The mark says the state of the folder, and sits on the middle of its text.
        Box(modifier = Modifier.size(24.dp), contentAlignment = Alignment.Center) {
            when (folder.mark()) {
                FolderMark.FINISHED -> CheckMark(24.dp)
                // Started but no photo finished yet: the ring turns; then it follows the real count.
                FolderMark.RUNNING -> GaugeRing(folder.gaugeFraction(), 24.dp)
                FolderMark.WAITING -> Hourglass(24.dp)
            }
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(folder.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            AnimatedVisibility(
                visible = showDetails,
                enter = fadeIn(tween(250)) + expandVertically(tween(250)),
                exit = fadeOut(tween(250)) + shrinkVertically(tween(250)),
            ) {
                Text(folderDetail(folder), fontSize = 12.sp, color = Palette.Muted)
            }
        }
    }
}
