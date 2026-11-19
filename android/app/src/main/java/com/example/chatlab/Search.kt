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
import androidx.compose.material3.FilterChip
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

    // In description mode, the sentence is turned into a vector once the typing pauses.
    LaunchedEffect(query, byDescription) {
        queryVector = null
        modelError = null
        if (byDescription && query.isNotBlank()) {
            delay(400)
            try {
                queryVector = withContext(Dispatchers.Default) { TextModels.get(context).encode(query) }
            } catch (error: Exception) {
                modelError = error.message ?: error.javaClass.simpleName
            }
        }
    }

    val matches: List<Match> = when {
        query.isBlank() -> emptyList()
        !byDescription -> Library.searchWords(entries, query, place)
        else -> queryVector?.let { Library.searchDescription(entries, it, place) } ?: emptyList()
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
                    Text(
                        "${matches.size} résultat(s)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(matches, key = { it.entry.id }) { match ->
                    ResultCard(match)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(match: Match) {
    val entry = match.entry
    Card(modifier = Modifier.fillMaxWidth()) {
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
