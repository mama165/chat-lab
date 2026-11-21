package com.example.chatlab

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.chatlab.ui.theme.AndroidTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidTheme {
                MainScreen()
            }
        }
    }
}

const val PREFERENCES = "chatlab"
const val KEY_FOLDER = "folder"
private const val KEY_SOURCE = "source"

private fun startFullAnalysis(context: Context, preferences: android.content.SharedPreferences) {
    val chatlab = preferences.getString(KEY_FOLDER, null)
    val source = preferences.getString(KEY_SOURCE, null)
    if (chatlab != null && source != null) AnalysisService.start(context, chatlab, source)
}

@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val preferences = remember { context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE) }
    var report by remember {
        mutableStateOf(
            if (preferences.getString(KEY_FOLDER, null) == null)
                "Choisissez d'abord le dossier chatlab (models et test à l'intérieur)."
            else "Appuyez sur le bouton pour analyser les images de test."
        )
    }
    var running by remember { mutableStateOf(false) }
    var sourceName by remember { mutableStateOf(preferences.getString(KEY_SOURCE, null)) }
    var libraryFolder by remember { mutableStateOf(preferences.getString(KEY_FOLDER, null)) }
    var showTests by remember { mutableStateOf(false) }
    val progress by AnalysisProgress.state.collectAsState()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }

    // Quand l'analyse complète se termine, son compte rendu remplace celui affiché et copié.
    LaunchedEffect(progress.report) {
        if (progress.report.isNotBlank()) report = progress.report
    }

    LaunchedEffect(progress.running) {
        while (progress.running) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }

    val chooseFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            // Garde l'accès au dossier après un redémarrage de l'application.
            // Lecture et écriture : le carnet est enregistré dans ce dossier.
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            preferences.edit().putString(KEY_FOLDER, uri.toString()).apply()
            libraryFolder = uri.toString()
            report = "Dossier enregistré. Appuyez sur le bouton pour analyser."
        }
    }

    val chooseSource = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            preferences.edit().putString(KEY_SOURCE, uri.toString()).apply()
            sourceName = uri.toString()
        }
    }

    // Sur Android 13 et plus, les notifications doivent être autorisées ; le traitement part dans tous les cas.
    val askNotifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        startFullAnalysis(context, preferences)
    }

    val fraction = if (progress.total > 0) progress.done.toFloat() / progress.total else 0f
    val startFull = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startFullAnalysis(context, preferences)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Mes photos", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.primary)
        Text(
            "Analysez un dossier pour retrouver vos images par mots ou par description.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("1 · Dossier de la bibliothèque", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (libraryFolder == null) "Non choisi" else "Choisi ✓",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text("Il contient le sous-dossier models.", style = MaterialTheme.typography.bodySmall)
                Button(onClick = { chooseFolder.launch(null) }) {
                    Text("Choisir le dossier chatlab")
                }
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("2 · Dossier à analyser", style = MaterialTheme.typography.labelLarge)
                Text(
                    if (sourceName == null) "Non choisi" else "Choisi ✓ (sous-dossiers inclus)",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = { chooseSource.launch(null) }) {
                    Text("Choisir le dossier à analyser")
                }
            }
        }

        Button(
            modifier = Modifier.fillMaxWidth(),
            enabled = !progress.running && libraryFolder != null && sourceName != null,
            onClick = startFull,
        ) {
            Text("Lancer l'analyse")
        }

        if (progress.running) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Analyse en cours : ${(fraction * 100).toInt()} %",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(fraction)
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.primary),
                        )
                    }
                    Text(progressLine(progress, now), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "Vous pouvez quitter l'application : le traitement continue.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        } else if (progress.message.isNotBlank()) {
            Text(progress.message, style = MaterialTheme.typography.bodyMedium)
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Dernier compte rendu", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        enabled = !running,
                        onClick = {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            clipboard.setPrimaryClip(ClipData.newPlainText("compte rendu", report))
                            Toast.makeText(context, "Compte rendu copié", Toast.LENGTH_SHORT).show()
                        },
                    ) {
                        Text("Copier")
                    }
                    Button(
                        enabled = !running,
                        onClick = {
                            val send = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, report)
                            }
                            context.startActivity(Intent.createChooser(send, "Partager le compte rendu"))
                        },
                    ) {
                        Text("Partager")
                    }
                }
                SelectionContainer {
                    Text(report, style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        TextButton(onClick = { showTests = !showTests }) {
            Text(if (showTests) "Masquer les outils de test" else "Outils de test ›")
        }
        if (showTests) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Analyse des images de test, dans le sous-dossier test du dossier chatlab.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(
                        enabled = !running,
                        onClick = {
                            val folder = preferences.getString(KEY_FOLDER, null)
                            if (folder == null) {
                                report = "Choisissez d'abord le dossier chatlab."
                            } else {
                                running = true
                                report = "Analyse en cours..."
                                scope.launch {
                                    report = withContext(Dispatchers.Default) { runAnalysis(context, Uri.parse(folder)) }
                                    running = false
                                }
                            }
                        },
                    ) {
                        Text("Analyser les images de test")
                    }
                }
            }
        }
    }
}

/** Lance l'analyse et met le compte rendu en forme, image par image. */
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
        }
    }
} catch (e: Exception) {
    "Erreur au chargement : ${e.message ?: e.javaClass.simpleName}"
}
