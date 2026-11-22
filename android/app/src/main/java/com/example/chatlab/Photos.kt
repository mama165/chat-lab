package com.example.chatlab

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.example.chatlab.ui.OutlineButton
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import com.example.chatlab.ui.StopSquare
import com.example.chatlab.ui.TabTitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Onglet Photos : le carnet, un seul bouton pour analyser, puis la progression avec Pause et Arrêter. */
@Composable
fun PhotosScreen(entries: List<LibraryEntry>, onSettings: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val progress by AnalysisProgress.state.collectAsState()
    var foldersVersion by remember { mutableIntStateOf(0) }
    val pickers = rememberFolderPickers { foldersVersion++ }
    val chatlab = remember(foldersVersion) { folderName(context, KEY_FOLDER) }
    val source = remember(foldersVersion) { folderName(context, KEY_SOURCE) }
    val lastDate = remember(progress.running, entries) { ReportStore.lastDate(context) }
    // Les photos pas encore dans le carnet, comptées en arrière-plan à l'ouverture de l'onglet.
    val newCount by produceState<Int?>(initialValue = null, entries, foldersVersion, progress.running) {
        value = if (progress.running) null else withContext(Dispatchers.IO) { newPhotosCount(context) }
    }
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
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Spacer(Modifier.height(0.dp))
        TabTitle("Photos", onSettings = onSettings)
        when {
            chatlab == null || source == null -> FirstSteps(chatlab, source, pickers)
            progress.running -> Running(progress, now)
            else -> Idle(entries.size, lastDate, newCount, progress.message, onStart = start)
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun startFullAnalysis(context: Context) {
    val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    val chatlab = preferences.getString(KEY_FOLDER, null)
    val source = preferences.getString(KEY_SOURCE, null)
    if (chatlab != null && source != null) AnalysisService.start(context, chatlab, source)
}

/** Premier lancement : choisir les deux dossiers. */
@Composable
private fun FirstSteps(chatlab: String?, source: String?, pickers: FolderPickers) {
    Text("Pour commencer, choisissez deux dossiers.", fontSize = 15.sp, color = Palette.Ink)
    Step(1, "Le dossier chatlab", "Il contient le sous-dossier models. Le carnet y est enregistré.", chatlab, pickers.chooseChatlab)
    Step(2, "Les photos à analyser", "Sous-dossiers compris. Les photos restent à leur place.", source, pickers.chooseSource)
}

@Composable
private fun Step(number: Int, title: String, detail: String, chosen: String?, onChoose: () -> Unit) {
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
        if (chosen != null) {
            Text("Choisi : $chosen ✓", fontSize = 14.sp, color = Palette.Accent)
        } else {
            PrimaryButton("Choisir", onClick = onChoose)
        }
    }
}

/** Au repos : le carnet, la dernière analyse et le bouton unique. */
@Composable
private fun Idle(count: Int, lastDate: SimpleDate?, newCount: Int?, message: String, onStart: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Palette.Field)
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("$count", fontSize = 40.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
        Text(if (count == 1) "photo dans le carnet" else "photos dans le carnet", fontSize = 15.sp, color = Palette.Ink)
        val parts = listOfNotNull(
            lastDate?.let { "Dernière analyse le ${Dates.short(it)}" },
            when (newCount) {
                null -> null
                0 -> "tout est à jour"
                1 -> "1 nouvelle photo"
                else -> "$newCount nouvelles photos"
            },
        )
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 13.sp, color = Palette.Muted)
    }
    val label = when {
        newCount == null -> "Analyser les nouvelles photos"
        newCount == 0 -> "Tout est à jour"
        count == 0 -> "Analyser les ${Formats.photos(newCount)}"
        newCount == 1 -> "Analyser la nouvelle photo"
        else -> "Analyser les $newCount nouvelles photos"
    }
    PrimaryButton(label, onClick = onStart, enabled = newCount != 0)
    Text(
        "Les photos déjà analysées ne sont pas reprises.",
        fontSize = 13.sp,
        color = Palette.Muted,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
    if (message.isNotBlank()) {
        Text(message, fontSize = 13.sp, color = Palette.Muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

/** Pendant l'analyse : anneau de progression, Pause ou Reprendre, et Arrêter. */
@Composable
private fun Running(progress: Progress, now: Long) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(modifier = Modifier.size(150.dp), contentAlignment = Alignment.Center) {
            val color = if (progress.paused) Palette.Disabled else Palette.Accent
            Canvas(modifier = Modifier.size(150.dp)) {
                val stroke = Stroke(width = 12.dp.toPx(), cap = StrokeCap.Round)
                val inset = 6.dp.toPx()
                val arcSize = androidx.compose.ui.geometry.Size(size.width - 2 * inset, size.height - 2 * inset)
                val topLeft = androidx.compose.ui.geometry.Offset(inset, inset)
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
            if (progress.total > 0) "${progress.done} sur ${progress.total} photos" else progress.message.ifBlank { "Préparation…" },
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
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (progress.paused) {
            PrimaryButton("▶ Reprendre", onClick = { AnalysisService.resume() }, modifier = Modifier.weight(1f))
        } else {
            PrimaryButton("❚❚ Pause", onClick = { AnalysisService.pause() }, modifier = Modifier.weight(1f))
        }
        OutlineButton("Arrêter", onClick = { AnalysisService.stop() }, modifier = Modifier.weight(1f), leading = { StopSquare() })
    }
    Text(
        "Pause : le calcul se fige tout de suite et reprend exactement là où il en était, quand vous appuyez sur Reprendre.\n" +
            "Arrêter : l'analyse se termine, les photos déjà faites sont gardées et ne seront pas refaites.",
        fontSize = 12.sp,
        color = Palette.Muted,
    )
    Text(
        "Vous pouvez quitter l'application : la notification suit l'analyse et propose les mêmes boutons.",
        fontSize = 12.sp,
        color = Palette.Muted,
    )
}
