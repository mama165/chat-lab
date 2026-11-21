package com.example.chatlab

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.BackHandler
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** L'écran principal : deux onglets, Rechercher et Analyser, tous deux toujours présents. */
@Composable
fun MainScreen() {
    var tab by remember { mutableIntStateOf(0) }
    Scaffold(
        topBar = {
            // With edge-to-edge display, the tabs must start below the status bar.
            Column(modifier = Modifier.statusBarsPadding()) {
                TabRow(selectedTabIndex = tab) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Rechercher") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Analyser") })
                }
            }
        },
    ) { innerPadding ->
        // Both screens stay in the composition, so an analysis keeps running while the search is open.
        // The hidden one is shrunk to nothing and clipped, so it neither shows nor takes touches.
        Box(modifier = Modifier.padding(innerPadding).fillMaxSize()) {
            SearchScreen(
                modifier = if (tab == 0) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds(),
                visible = tab == 0,
            )
            TestScreen(
                modifier = if (tab == 1) Modifier.fillMaxSize() else Modifier.size(0.dp).clipToBounds(),
            )
        }
    }
}

/** Recherche : par mots dans le texte lu, ou par description (la phrase est comparée aux images). */
@Composable
fun SearchScreen(modifier: Modifier = Modifier, visible: Boolean = true) {
    val context = LocalContext.current
    // Reloaded each time the tab is opened, to see the latest analysis.
    val entries = remember(visible) { Library.load(context) }
    var query by remember { mutableStateOf("") }
    var place by remember { mutableStateOf(Place.ALL) }
    var byDescription by remember { mutableStateOf(false) }
    var queryVector by remember { mutableStateOf<FloatArray?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var encodeMillis by remember { mutableStateOf<Long?>(null) }
    var selected by remember { mutableStateOf<Match?>(null) }

    // In description mode, the sentence is turned into a vector once the typing pauses.
    LaunchedEffect(query, byDescription) {
        queryVector = null
        modelError = null
        encodeMillis = null
        if (byDescription && query.isNotBlank()) {
            delay(400)
            try {
                val started = System.nanoTime()
                queryVector = withContext(Dispatchers.Default) { TextModels.vectorFor(context, query) }
                encodeMillis = (System.nanoTime() - started) / 1_000_000
            } catch (error: CancellationException) {
                // The query changed again or the screen left the composition: not an error to show.
                throw error
            } catch (error: Exception) {
                modelError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    val criteria = remember(query) { Library.criteria(query) }
    val searchStart = System.nanoTime()
    val matches: List<Match> = when {
        query.isBlank() -> emptyList()
        !byDescription -> Library.searchWords(entries, query, place)
        else -> queryVector?.let { Library.searchDescription(entries, it, criteria.place ?: place, criteria.people) } ?: emptyList()
    }
    val matchMillis = (System.nanoTime() - searchStart) / 1_000_000
    // Temps de la recherche, affiché sous le compteur de résultats.
    val timingLine = when {
        query.isBlank() || (byDescription && queryVector == null) -> ""
        !byDescription -> "Recherche par mots : $matchMillis ms"
        else -> "Lecture de la description : ${encodeMillis ?: 0} ms · classement : $matchMillis ms"
    }

    // Le détail remplace la liste ; la recherche reste en mémoire pour revenir au même endroit.
    selected?.let { match ->
        BackHandler { selected = null }
        ResultDetail(match, modifier) { selected = null }
        return
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(if (byDescription) "Décrivez ce que vous cherchez" else "Ce que vous cherchez") },
            placeholder = {
                Text(
                    if (byDescription) "une cuisine, deux personnes, une plage…"
                    else "facture, wifi, sortie…",
                )
            },
            singleLine = true,
            // A small button to clear the text at once, shown only when there is some.
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { query = "" }) {
                        Text("✕")
                    }
                }
            },
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = !byDescription,
                onClick = { byDescription = false },
                label = { Text("Mots") },
            )
            FilterChip(
                selected = byDescription,
                onClick = { byDescription = true },
                label = { Text("Description") },
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = place == Place.ALL,
                onClick = { place = Place.ALL },
                label = { Text("Toutes") },
            )
            FilterChip(
                selected = place == Place.INDOOR,
                onClick = { place = Place.INDOOR },
                label = { Text("Intérieur") },
            )
            FilterChip(
                selected = place == Place.OUTDOOR,
                onClick = { place = Place.OUTDOOR },
                label = { Text("Extérieur") },
            )
        }
        Spacer(Modifier.height(16.dp))
        if (byDescription && (criteria.people != null || criteria.place != null)) {
            val parts = listOfNotNull(
                criteria.people?.let { "$it personne(s)" },
                criteria.place?.let { if (it == Place.INDOOR) "en intérieur" else "en extérieur" },
            )
            Text(
                "Critères compris : ${parts.joinToString(" · ")}",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.height(8.dp))
        }

        when {
            entries.isEmpty() -> Text(
                "Aucune image analysée pour l'instant. Ouvrez l'onglet Analyser, puis lancez l'analyse.",
                style = MaterialTheme.typography.bodyMedium,
            )
            modelError != null -> Text(
                "Recherche par description indisponible : $modelError",
                style = MaterialTheme.typography.bodyMedium,
            )
            byDescription && query.isNotBlank() && queryVector == null -> Text(
                "Lecture de la description…",
                style = MaterialTheme.typography.bodyMedium,
            )
            query.isBlank() -> Text(
                "${entries.size} image(s) dans le carnet. Tapez un mot, ou une description.",
                style = MaterialTheme.typography.bodyMedium,
            )
            matches.isEmpty() -> Text(
                "Aucune image ne correspond à « $query ».",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(timingLine, style = MaterialTheme.typography.labelSmall)
                }
                item {
                    Text(
                        if (byDescription) "Les ${matches.size} images les plus proches" else "${matches.size} résultat(s)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(matches, key = { it.entry.id }) { match ->
                    ResultCard(match) { selected = match }
                }
            }
        }
    }
}

@Composable
private fun ResultCard(match: Match, onClick: () -> Unit) {
    val entry = match.entry
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Thumbnail(entry.path)
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    summary(entry),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (entry.taken.isNotBlank()) {
                    Text(
                        "Prise le ${entry.taken}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                match.score?.let {
                    Text(
                        "ressemblance ${"%.3f".format(it)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (entry.text.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        entry.text,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumbnail(path: String) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(path)?.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Écran de détail d'une photo : aperçu en grand, date de prise de vue, lieu, visages, texte lu. */
@Composable
private fun ResultDetail(match: Match, modifier: Modifier, onBack: () -> Unit) {
    val entry = match.entry
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TextButton(onClick = onBack) {
            Text("‹ Retour aux résultats")
        }
        Preview(entry.path)
        Text(entry.name, style = MaterialTheme.typography.titleMedium)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DetailLine("Prise le", entry.taken.ifBlank { "date inconnue" })
                DetailLine(
                    "Lieu",
                    when (entry.indoor) {
                        true -> "Intérieur"
                        false -> "Extérieur"
                        null -> "Inconnu"
                    },
                )
                DetailLine("Visages", entry.faces.toString())
                match.score?.let { DetailLine("Ressemblance", "%.3f".format(it)) }
            }
        }
        if (entry.text.isNotBlank()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Texte lu sur la photo", style = MaterialTheme.typography.labelMedium)
                    Text(entry.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun DetailLine(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Aperçu de la photo en grand : décodé à une taille raisonnable, pas en pleine résolution. */
@Composable
private fun Preview(path: String) {
    val image by produceState<ImageBitmap?>(initialValue = null, path) {
        value = withContext(Dispatchers.IO) { decodePreview(path)?.asImageBitmap() }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        image?.let {
            Image(
                bitmap = it,
                contentDescription = null,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

private fun decodePreview(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 1000 && bounds.outHeight / (sample * 2) >= 1000) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

/** Décode l'image à petite taille, pour que la liste reste fluide. */
private fun decodeThumbnail(path: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
}

private fun summary(entry: LibraryEntry): String {
    val place = when (entry.indoor) {
        true -> "intérieur"
        false -> "extérieur"
        null -> "lieu inconnu"
    }
    val people = when (entry.faces) {
        0 -> "sans visage"
        1 -> "1 visage"
        else -> "${entry.faces} visages"
    }
    return "$place · $people"
}
