package com.example.chatlab

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            AndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    TestScreen(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

@Composable
fun TestScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf("Appuyez sur le bouton pour analyser les images de test.") }
    var running by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Button(
            enabled = !running,
            onClick = {
                running = true
                report = "Analyse en cours..."
                scope.launch {
                    report = withContext(Dispatchers.Default) { runAnalysis(context) }
                    running = false
                }
            },
        ) {
            Text("Analyser les images de test")
        }
        Spacer(Modifier.height(16.dp))
        Text(report)
    }
}

/** Lance l'analyse et met le compte rendu en forme, image par image. */
fun runAnalysis(context: Context): String = try {
    Analyzer(context).use { analyzer ->
        val started = System.nanoTime()
        val results = analyzer.analyzeAll()
        val total = (System.nanoTime() - started) / 1_000_000
        buildString {
            if (results.isEmpty()) appendLine("Aucune image trouvée dans le dossier test.")
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
                }
            }
            val library = analyzer.writeLibrary(results)
            appendLine()
            appendLine("Total : $total ms pour ${results.size} image(s).")
            appendLine("Carnet écrit : ${library.path}")
        }
    }
} catch (e: Exception) {
    "Erreur au chargement : ${e.message ?: e.javaClass.simpleName}"
}
