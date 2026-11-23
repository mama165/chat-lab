package com.example.chatlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
    val reading by ReadProgress.counts.collectAsState()
    val totals by ReadProgress.totalCounts.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val pickers = rememberFolderPickers(onAddSource = onAddFolders) { version++ }
    val chatlab = remember(version) { folderName(context, KEY_FOLDER) }
    val sources = remember(version) { Sources.read(context) }
    // What the folders contain, read in the background: no analysis, only a listing.
    val overviews by produceState<List<FolderOverview>?>(initialValue = null, version, entries, progress.running) {
        value = if (progress.running) null else withContext(Dispatchers.IO) { overview(context) }
    }
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
        TabTitle("Photos", onSettings = onSettings)
        when {
            chatlab == null || sources.isEmpty() -> FirstSteps(chatlab, sources, pickers)
            progress.running -> Running(progress, now)
            else -> Idle(
                entries = entries,
                chatlab = chatlab,
                sources = sources,
                reading = reading,
                totals = totals,
                overviews = overviews,
                lastRun = lastRun,
                message = progress.message,
                pickers = pickers,
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

/** At rest: report of the last analysis, folders and their state, analysis button. */
@Composable
private fun Idle(
    entries: List<LibraryEntry>,
    chatlab: String,
    sources: List<SourceFolder>,
    reading: Map<String, Int>,
    totals: Map<String, Int>,
    overviews: List<FolderOverview>?,
    lastRun: RunReport?,
    message: String,
    pickers: FolderPickers,
    onRemove: (String) -> Unit,
    onStart: () -> Unit,
    onHistory: () -> Unit,
) {
    val subtitle = buildString {
        append("${Formats.photos(entries.size).replaceFirstChar { it.uppercase() }} dans le carnet")
        lastRun?.let { append(" · dernière analyse le ${Dates.short(SimpleDate.of(it.finishedAt))}") }
    }
    Text(subtitle, fontSize = 13.sp, color = Palette.Muted, modifier = Modifier.padding(top = 0.dp))
    if (lastRun != null) RunBanner(lastRun, onHistory)

    SectionLabel("Dossiers à analyser", right = "sous-dossiers inclus")
    // Each folder shows at once: its card if its reading is done, otherwise its reading in progress.
    // So a folder just added does not wait for all the others to be read.
    sources.forEach { folder ->
        val overview = overviews?.firstOrNull { it.status.folder.uri == folder.uri }
        if (overview != null) {
            val failures = lastRun?.folders?.firstOrNull { it.uri == folder.uri }?.failures.orEmpty()
            FolderCard(overview, failures, onRemove = { onRemove(folder.uri) })
        } else {
            ReadingCard(folder, reading[folder.uri] ?: 0, totals[folder.uri] ?: 0)
        }
        // The same grey line between each folder, as in the mockup.
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
    }
    AddFolderRow(pickers.addSource)
    Text("Les photos restent dans leur dossier. Seules les nouvelles sont analysées.", fontSize = 12.sp, color = Palette.Muted)

    val fresh = overviews?.sumOf { it.fresh }
    val retry = lastRun?.unread ?: 0
    val newOnes = fresh?.let { (it - retry).coerceAtLeast(0) }
    when {
        newOnes != null && newOnes > 0 ->
            PrimaryButton(if (newOnes == 1) "Analyser la nouvelle photo" else "Analyser les $newOnes nouvelles photos", onClick = onStart)
        retry > 0 || lastRun != null -> OutlineButton("Relancer l'analyse", onClick = onStart, modifier = Modifier.fillMaxWidth())
        fresh == null -> PrimaryButton("Analyser les nouvelles photos", onClick = onStart, enabled = false)
        else -> PrimaryButton("Tout est à jour", onClick = onStart, enabled = false)
    }
    if (message.isNotBlank()) {
        Text(message, fontSize = 13.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
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

/** A folder being read: its name, and the number of files examined so far. */
@Composable
private fun ReadingCard(folder: SourceFolder, examined: Int, total: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Dossier ${folder.name}, lecture en cours" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // La jauge suit le vrai avancement : les fichiers examinés sur le total du dossier.
        // Tant que le total n'est pas connu, un tour continu indique seulement que ça tourne.
        GaugeRing(if (total > 0) Progress01.of(examined, total) else null)
        Column(modifier = Modifier.padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(16.dp))
                Text(folder.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            }
            Text(
                when {
                    total > 0 -> "Lecture en cours · $examined sur $total fichiers"
                    examined == 0 -> "Lecture en cours…"
                    else -> "Lecture en cours · $examined fichiers examinés"
                },
                fontSize = 12.sp,
                color = Palette.Muted,
            )
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

/** A folder: its state (readable, refused, empty), its problems, and what is needed to remove it from the list. */
@Composable
private fun FolderCard(overview: FolderOverview, failures: List<FileFailure>, onRemove: () -> Unit) {
    val status = overview.status
    val problem = status.state == FolderStatus.State.REFUSED || status.refused.isNotEmpty() || failures.isNotEmpty()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .semantics { contentDescription = "Dossier ${status.folder.name}" },
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (problem) Palette.Field else Palette.Soft),
            contentAlignment = Alignment.Center,
        ) {
            if (problem) Text("!", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Palette.Danger)
            else Icon(painterResource(R.drawable.ic_folder), contentDescription = null, tint = Palette.Accent, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(status.folder.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            when (status.state) {
                FolderStatus.State.REFUSED -> {
                    Text("Accès refusé par Android", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Danger)
                    Text("Aucune photo ne sera analysée depuis ce dossier.", fontSize = 12.sp, color = Palette.Muted)
                }
                FolderStatus.State.EMPTY -> {
                    Text("Aucune image à analyser", fontSize = 12.sp, color = Palette.Muted)
                    if (status.ignored > 0) Text("${status.ignored} fichiers ignorés (pas des images lisibles)", fontSize = 12.sp, color = Palette.Muted)
                }
                FolderStatus.State.READABLE -> {
                    Text("${Formats.photos(status.photos)} · ${overview.known} dans le carnet", fontSize = 12.sp, color = Palette.Muted)
                    Text(
                        if (overview.fresh == 0) "Rien de nouveau" else if (overview.fresh == 1) "1 nouvelle" else "${overview.fresh} nouvelles",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent,
                    )
                    if (status.ignored > 0) Text("${status.ignored} fichiers ignorés (pas des images lisibles)", fontSize = 11.sp, color = Palette.Muted)
                }
            }
            if (status.refused.isNotEmpty()) {
                ProblemBox(
                    if (status.refused.size == 1) "1 sous-dossier refusé" else "${status.refused.size} sous-dossiers refusés",
                    status.refused.map { it to "accès refusé par Android" },
                )
            }
            if (failures.isNotEmpty()) {
                ProblemBox(
                    if (failures.size == 1) "1 fichier non lu" else "${failures.size} fichiers non lus",
                    failures.map { it.name to it.reason.label },
                )
            }
            if (problem) {
                Text(
                    "Retirer ce dossier de l'analyse",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Accent,
                    modifier = Modifier.clickable(onClick = onRemove).padding(vertical = 2.dp),
                )
            }
        }
        Text(
            "✕",
            fontSize = 16.sp,
            color = Palette.Muted,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRemove)
                .semantics { contentDescription = "Retirer ${status.folder.name}" }
                .padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** A red box: a title, then each file with its reason in brackets. */
@Composable
private fun ProblemBox(title: String, items: List<Pair<String, String>>) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFFFBEAEA))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Danger)
        items.forEach { (name, reason) ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(name, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = Palette.Ink, modifier = Modifier.weight(1f, fill = false))
                Text("($reason)", fontSize = 11.sp, color = Palette.Muted)
            }
        }
    }
}

@Composable
private fun AddFolderRow(onClick: () -> Unit) {
    // As in mockup 1.3: a light blue square with a plus, then "Nouveau dossier".
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(Palette.Soft),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(20.dp)) {
                val w = size.width
                val h = size.height
                drawLine(Palette.Accent, Offset(w / 2, 0f), Offset(w / 2, h), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
                drawLine(Palette.Accent, Offset(0f, h / 2), Offset(w, h / 2), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Spacer(Modifier.width(12.dp))
        Text("Nouveau dossier", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
    }
}

/** During the analysis: the running list, the pause (view 2.2) or the reloading of the models (view 2.3). */
@Composable
private fun Running(progress: Progress, now: Long) {
    val reloading = progress.loadingModels > 0
    var holding by remember { mutableStateOf(false) }
    // Once the models are ready, the reloading view stays a moment, so that its three rows can be read.
    LaunchedEffect(reloading) {
        if (!reloading && progress.modelSteps.isNotEmpty()) {
            holding = true
            delay(RELOAD_HOLD_MS)
            holding = false
        }
    }
    when {
        reloading || holding -> ReloadingScreen(progress, loading = reloading)
        progress.runningScreen() == RunningScreen.PAUSED -> PausedScreen(progress)
        else -> RunningDetail(progress, now)
    }
}

private const val RELOAD_HOLD_MS = 2500L

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

/**
 * Reprise (view 2.3): the three models, each waiting, loading (turning) or done with its real
 * loading time; then the photos go on.
 */
@Composable
private fun ReloadingScreen(progress: Progress, loading: Boolean) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(modifier = Modifier.size(130.dp), color = Palette.Accent, strokeWidth = 10.dp)
        } else {
            Canvas(modifier = Modifier.size(130.dp)) {
                val stroke = Stroke(width = 10.dp.toPx())
                drawArc(Palette.Accent, 0f, 360f, false, Offset(5.dp.toPx(), 5.dp.toPx()), Size(size.width - 10.dp.toPx(), size.height - 10.dp.toPx()), style = stroke)
            }
        }
        Text(
            if (loading) "Chargement des modèles" else "Modèles prêts",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Ink,
        )
        Text(
            if (loading) "Les photos reprendront une fois les modèles prêts." else "Les photos reprennent.",
            fontSize = 13.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
    }
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

/** The running list: progress ring, progress per folder, Pause, and Arrêter. */
@Composable
private fun RunningDetail(progress: Progress, now: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            val color = if (progress.paused) Palette.Disabled else Palette.Accent
            Canvas(modifier = Modifier.size(150.dp)) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                val arcSize = Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = Offset(inset, inset)
                drawArc(Palette.Field, 0f, 360f, false, topLeft, arcSize, style = stroke)
                drawArc(color, -90f, 360f * progress.fraction, false, topLeft, arcSize, style = stroke)
            }
            Text(
                if (progress.paused) "En pause" else "${(progress.fraction * 100).toInt()} %",
                fontSize = if (progress.paused) 20.sp else 26.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
            )
        }
        Text(
            when {
                progress.total > 0 -> "${progress.done} sur ${progress.total} nouvelles photos"
                progress.message == "Rien de nouveau" -> "Rien de nouveau à analyser"
                else -> progress.message.ifBlank { "Préparation…" }
            },
            fontSize = 16.sp,
            color = Palette.Ink,
        )
        Text(
            when {
                progress.paused -> "Rien ne tourne · ${Formats.photos(progress.done)} déjà faite${if (progress.done > 1) "s" else ""}, rien n'est perdu"
                progress.message == "Arrêt en cours…" -> "Arrêt en cours, ce qui est fait est enregistré…"
                else -> remainingText(progress.remaining(now))
            },
            fontSize = 13.sp,
            color = Palette.Muted,
            textAlign = TextAlign.Center,
        )
    }
    if (progress.folders.isNotEmpty()) {
        SectionLabel("Par dossier")
        progress.folders.forEach { FolderProgressRow(it) }
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
        "Un dossier sans rien de nouveau est marqué d'une coche : il n'est pas relu. Vous pouvez quitter l'application : la notification suit l'analyse et propose les mêmes boutons.",
        fontSize = 12.sp,
        color = Palette.Muted,
    )
}

@Composable
private fun FolderProgressRow(folder: FolderProgress) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // La marque de gauche dit l'état du dossier : une coche, une jauge qui suit ses photos, ou un sablier.
        val done = folder.mark() == FolderMark.FINISHED
        Box(modifier = Modifier.size(20.dp), contentAlignment = Alignment.Center) {
            when (folder.mark()) {
                FolderMark.FINISHED -> CheckMark()
                // Started but no photo finished yet: the ring turns; then it follows the real count.
                FolderMark.RUNNING -> GaugeRing(folder.gaugeFraction())
                FolderMark.WAITING -> Hourglass()
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(folder.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
            Text(if (folder.nothingNew) "Déjà dans le carnet" else "Nouvelles photos", fontSize = 12.sp, color = Palette.Muted)
            if (!done && folder.current.isNotBlank()) Text("En cours : ${folder.current}", fontSize = 11.sp, color = Palette.Muted)
        }
        Text(if (folder.nothingNew) "rien à faire" else "${folder.done} / ${folder.total}", fontSize = 13.sp, color = Palette.Accent)
    }
}
