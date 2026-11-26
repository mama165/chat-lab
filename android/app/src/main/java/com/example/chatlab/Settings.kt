package com.example.chatlab

import android.app.usage.StorageStatsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import com.example.chatlab.ui.OutlineButton
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import com.example.chatlab.ui.RadioRow
import com.example.chatlab.ui.SectionLabel
import com.example.chatlab.ui.SettingRow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Settings page, opened by the gear: folders, unclassified, space, tools, erase everything. */
@Composable
fun SettingsPage(
    entries: List<LibraryEntry>,
    dataVersion: Int,
    removalsVersion: Int,
    onBack: () -> Unit,
    onOpen: (Page) -> Unit,
    onFoldersChanged: () -> Unit,
    onAddFolders: () -> Unit,
) {
    val context = LocalContext.current
    var foldersVersion by remember { mutableIntStateOf(0) }
    val pickers = rememberFolderPickers(onAddSource = onAddFolders) {
        foldersVersion++
        onFoldersChanged()
    }
    val sources = remember(foldersVersion) { Sources.read(context) }
    val chatlab = remember(foldersVersion) { folderName(context, KEY_FOLDER) }
    val removals = remember(removalsVersion) { RemovalsJson.count(LibraryStore.readRemovals(context)) }
    val history = remember(dataVersion) { RunArchive.list(context).size }
    val appSize by produceState<Long?>(initialValue = null, dataVersion) {
        value = withContext(Dispatchers.IO) { measureAppSize(context) }
    }
    val librarySize by produceState(initialValue = 0L, dataVersion) {
        value = withContext(Dispatchers.IO) { LibraryStore.librarySize(context) }
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
        PageHeader("Réglages", onBack = onBack)
        Column {
            SectionLabel("Dossiers")
            SettingRow(
                "Photos à analyser",
                when (sources.size) {
                    0 -> "à choisir"
                    1 -> sources.single().name
                    else -> "${sources.size} dossiers"
                },
                onClick = pickers.addSource,
            )
            SettingRow("Dossier chatlab", chatlab ?: "à choisir", onClick = pickers.chooseChatlab)
        }
        Column {
            SectionLabel("Recherche")
            SettingRow("Non classifiées", "$removals", onClick = { onOpen(Page.REMOVED) })
        }
        Column {
            SectionLabel("Analyse")
            SettingRow("Performance", LevelStore.read(context).label, onClick = { onOpen(Page.LEVEL) })
        }
        Column {
            SectionLabel("Espace")
            SettingRow("Application", appSize?.let { Formats.size(it) } ?: "…")
            SettingRow(
                "Carnet d'analyse",
                if (entries.isEmpty()) "vide" else "${Formats.photos(entries.size)} · ${Formats.size(librarySize)}",
            )
        }
        Column {
            SectionLabel("Avancé")
            SettingRow("Historique des analyses", "$history", onClick = { onOpen(Page.HISTORY) })
            SettingRow(
                "Exporter la bibliothèque",
                "sauvegarde library.json",
                onClick = {
                    Thread {
                        val message = try {
                            "Bibliothèque exportée : ${LibraryExport.run(context)} photos"
                        } catch (e: Exception) {
                            "Export impossible : ${e.message ?: e.javaClass.simpleName}"
                        }
                        context.mainExecutor.execute { Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                    }.start()
                },
            )
            SettingRow("Outils de test", onClick = { onOpen(Page.TESTS) })
            SettingRow("Tout effacer", color = Palette.Danger, onClick = { onOpen(Page.WIPE) })
        }
        Text(
            "Tout effacer remet l'application comme au premier jour, sans toucher aux photos ni aux modèles.",
            fontSize = 12.sp,
            color = Palette.Muted,
        )
        Spacer(Modifier.height(16.dp))
    }
}

/** Size of the application according to Android (program, data and cache), or that of the APK otherwise. */
private fun measureAppSize(context: Context): Long {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        try {
            val manager = context.getSystemService(StorageStatsManager::class.java)
            val stats = manager.queryStatsForPackage(StorageManager.UUID_DEFAULT, context.packageName, Process.myUserHandle())
            return stats.appBytes + stats.dataBytes + stats.cacheBytes
        } catch (e: Exception) {
            // Repli ci-dessous.
        }
    }
    return File(context.applicationInfo.sourceDir).length()
}

/** "Tout effacer" page: what disappears, what stays, and the red button. */
@Composable
fun WipePage(entries: List<LibraryEntry>, onBack: () -> Unit, onWiped: () -> Unit) {
    val context = LocalContext.current
    val progress by AnalysisProgress.state.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    val summary by produceState<WipeSummary?>(initialValue = null, version, entries) {
        value = withContext(Dispatchers.IO) {
            WipeSummary(
                photos = entries.size,
                librarySize = LibraryStore.librarySize(context),
                removals = RemovalsJson.count(LibraryStore.readRemovals(context)),
                recents = RecentStore.read(context),
                lastAnalysis = ReportStore.lastDate(context),
                hasReport = ReportStore.read(context) != null,
            )
        }
    }
    val chatlab = remember { folderName(context, KEY_FOLDER) ?: "chatlab" }
    val sourceNames = remember { Sources.read(context).map { it.name } }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Tout effacer", onBack = onBack)
        val current = summary
        Column(
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 8.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text(
                if (current != null && !current.canWipe) "Rien à effacer : l'application est déjà dans son état de départ."
                else "Les dossiers à analyser sont vidés. Le dossier chatlab reste choisi. La prochaine analyse reprendra toutes les photos depuis le début.",
                fontSize = 14.sp,
                color = Palette.Muted,
            )
            Column {
                SectionLabel("Disparaît")
                current?.lines()?.forEach { line ->
                    WipeRow(if (line.empty) "–" else "✕", line.title, line.detail, if (line.empty) Palette.Disabled else Palette.Danger, line.empty)
                }
                if (sourceNames.isEmpty()) {
                    WipeRow("–", "Les dossiers à analyser", "Aucun dossier choisi", Palette.Disabled, true)
                } else {
                    WipeRow("✕", "Les dossiers à analyser", sourceNames.joinToString(", "), Palette.Danger, false)
                }
            }
            Column {
                SectionLabel("Reste")
                WipeRow("✓", "Vos photos", "Rien n'est supprimé dans la galerie", Palette.Muted, false)
                WipeRow("✓", "Les modèles", "Dans $chatlab/models", Palette.Muted, false)
                WipeRow("✓", "Le dossier chatlab", chatlab, Palette.Muted, false)
            }
        }
        Column(modifier = Modifier.padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val canWipe = current?.canWipe == true && !progress.running
            PrimaryButton(
                "Tout effacer",
                enabled = canWipe,
                color = Palette.Danger,
                onClick = {
                    LibraryStore.wipeAnalysis(context)
                    RecentStore.clear(context)
                    ReportStore.clear(context)
                    AnalysisProgress.clearReport()
                    Sources.clear(context)
                    Toast.makeText(context, "Tout est effacé.", Toast.LENGTH_SHORT).show()
                    onWiped()
                    version++
                },
            )
            Text(
                when {
                    progress.running -> "Arrêtez d'abord l'analyse en cours."
                    current?.canWipe == false -> "Le bouton s'active dès qu'il y a quelque chose à effacer."
                    else -> "Pour annuler, revenez en arrière avec la flèche."
                },
                fontSize = 12.sp,
                color = Palette.Muted,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun WipeRow(icon: String, title: String, detail: String, iconColor: androidx.compose.ui.graphics.Color, empty: Boolean) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Top) {
        Text(icon, fontSize = 15.sp, color = iconColor, modifier = Modifier.width(24.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, color = if (empty) Palette.Muted else Palette.Ink)
            Text(detail, fontSize = 12.sp, color = Palette.Muted)
        }
    }
}

/** The analysis of the test images runs apart: leaving the page does not interrupt it. */
private object TestRun {
    val running = MutableStateFlow(false)
    val report = MutableStateFlow<String?>(null)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

/** "Outils de test" page: analysis of the test images and last report. */
@Composable
fun TestsPage(onBack: () -> Unit) {
    val context = LocalContext.current
    val running by TestRun.running.collectAsState()
    val testReport by TestRun.report.collectAsState()
    var copied by remember { mutableStateOf(false) }
    val report = testReport ?: ReportStore.read(context) ?: "Aucun compte rendu pour l'instant."
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Outils de test", onBack = onBack)
        Text(
            "Analyse les images du sous-dossier test du dossier chatlab. Le carnet est remplacé par ces images.",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        // Experiment on the memory of the models: it applies to the analyses started after the change.
        var arenaOn by remember { mutableStateOf(OnnxExperiment.arenaOn(context)) }
        Text("Mémoire des modèles", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
        RadioRow(
            label = "Mémoire ONNX gardée (réglage actuel)",
            count = "arène activée",
            selected = arenaOn,
            onClick = {
                arenaOn = true
                OnnxExperiment.writeArena(context, true)
            },
        )
        RadioRow(
            label = "Mémoire ONNX rendue (essai)",
            count = "arène désactivée",
            selected = !arenaOn,
            onClick = {
                arenaOn = false
                OnnxExperiment.writeArena(context, false)
            },
        )
        // Experiment on the threads of the ONNX sessions: 0 leaves the choice to ONNX Runtime.
        var threads by remember { mutableStateOf(OnnxExperiment.read(context).threads) }
        Text("Threads par session (essai)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
        OnnxExperiment.THREAD_CHOICES.forEach { n ->
            RadioRow(
                label = if (n == 0) "Automatique (réglage actuel)" else "$n threads",
                count = if (n == 0) "ONNX Runtime" else "essai",
                selected = threads == n,
                onClick = {
                    threads = n
                    OnnxExperiment.writeThreads(context, n)
                },
            )
        }
        // Optional, test only: forces the number of workers, bypassing the start memory budget.
        var forcedWorkers by remember { mutableStateOf(WorkerOverride.read(context)) }
        Text("Nombre de workers (essai)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
        RadioRow(
            label = "Automatique (réglage actuel)",
            count = "selon la mémoire",
            selected = forcedWorkers == null,
            onClick = {
                forcedWorkers = null
                WorkerOverride.write(context, null)
            },
        )
        (1..4).forEach { n ->
            RadioRow(
                label = "$n worker${if (n > 1) "s" else ""} forcé${if (n > 1) "s" else ""}",
                count = "essai",
                selected = forcedWorkers == n,
                onClick = {
                    forcedWorkers = n
                    WorkerOverride.write(context, n)
                },
            )
        }
        PrimaryButton(
            if (running) "Analyse en cours…" else "Analyser les images de test",
            enabled = !running,
            onClick = {
                val folder = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
                if (folder == null) {
                    TestRun.report.value = "Choisissez d'abord le dossier chatlab."
                } else {
                    TestRun.running.value = true
                    TestRun.report.value = "Analyse en cours…"
                    val app = context.applicationContext
                    TestRun.scope.launch {
                        TestRun.report.value = runAnalysis(app, Uri.parse(folder))
                        TestRun.running.value = false
                    }
                }
            },
        )
        SectionLabel("Dernier compte rendu")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlineButton(if (copied) "Copié" else "Copier", onClick = {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("compte rendu", report))
                copied = true
            }, modifier = Modifier.weight(1f))
            OutlineButton("Partager", onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, report)
                }
                context.startActivity(Intent.createChooser(send, "Partager le compte rendu"))
            }, modifier = Modifier.weight(1f))
        }
        SelectionContainer {
            Text(report, fontSize = 12.sp, color = Palette.Ink)
        }
        Spacer(Modifier.height(16.dp))
    }
}

/** Analyses the test images and formats the report, image by image. */
fun runAnalysis(context: Context, folder: Uri): String = try {
    Analyzer(context, folder).use { analyzer ->
        val started = System.nanoTime()
        val results = analyzer.analyzeAll()
        val total = (System.nanoTime() - started) / 1_000_000
        buildString {
            if (results.isEmpty()) appendLine("Aucune image trouvée dans le sous-dossier test.")
            for (r in results) {
                if (r.error != null) {
                    appendLine("${r.file.name} : erreur, ${r.error}")
                } else {
                    val place = when (r.indoor) {
                        true -> "intérieur"
                        false -> "extérieur"
                        null -> "inconnu"
                    }
                    appendLine("${r.file.name} : $place, ${r.faces} visage(s), ${r.millis} ms")
                    if (r.text.isNotBlank()) appendLine("  texte : ${r.text}") else appendLine("  texte : rien lu (${r.textNote})")
                    for (line in r.textTrace.lines().filter { it.isNotBlank() }) appendLine("  zone $line")
                }
            }
            val library = analyzer.writeLibrary(results)
            appendLine()
            appendLine("Total : $total ms pour ${results.size} image(s).")
            appendLine("Carnet écrit : ${library.path}")
            appendLine(runCatching { TextModels.control(context) }.getOrElse { "contrôle texte : ${it.message}" })
        }.also { ReportStore.save(context, it) }
    }
} catch (e: Exception) {
    "Erreur au chargement : ${e.message ?: e.javaClass.simpleName}"
}
