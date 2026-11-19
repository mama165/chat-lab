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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** L'écran principal : deux onglets, Rechercher et Analyser. */
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
        if (tab == 0) {
            SearchScreen(modifier = Modifier.padding(innerPadding))
        } else {
            TestScreen(modifier = Modifier.padding(innerPadding))
        }
    }
}

/** Recherche par mot-clé dans le texte lu sur les images. */
@Composable
fun SearchScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    // Rechargé à chaque ouverture de l'onglet, pour voir la dernière analyse.
    val entries = remember { Library.load(context) }
    var query by remember { mutableStateOf("") }
    var place by remember { mutableStateOf(Place.ALL) }
    val results = remember(query, place, entries) { Library.search(entries, query, place) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Ce que vous cherchez") },
            placeholder = { Text("facture, wifi, sortie…") },
            singleLine = true,
        )
        Spacer(Modifier.height(12.dp))
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
            query.isBlank() -> Text(
                "${entries.size} image(s) dans le carnet. Tapez un mot pour chercher dans le texte des images.",
                style = MaterialTheme.typography.bodyMedium,
            )
            results.isEmpty() -> Text(
                "Aucune image ne contient « $query ».",
                style = MaterialTheme.typography.bodyMedium,
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                item {
                    Text(
                        "${results.size} résultat(s)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(results, key = { it.id }) { entry ->
                    ResultCard(entry)
                }
            }
        }
    }
}

@Composable
private fun ResultCard(entry: LibraryEntry) {
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
