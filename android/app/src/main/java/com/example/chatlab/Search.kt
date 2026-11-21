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
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.TextButton
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.material3.Slider
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
    var selectedId by remember { mutableStateOf<String?>(null) }
    // Seuil réglé avec la jauge : le compte se met à jour tout de suite, la liste un peu après.
    var threshold by remember { mutableStateOf(0.08f) }
    var shownThreshold by remember { mutableStateOf(0.08f) }
    LaunchedEffect(threshold) {
        delay(300)
        shownThreshold = threshold
    }

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
    val ranked: List<Match> = remember(queryVector, place, criteria, entries) {
        queryVector?.let { Library.rankDescription(entries, it, criteria.place ?: place, criteria.people) } ?: emptyList()
    }
    val aboveNow = Library.aboveThreshold(ranked, threshold).size
    val matches: List<Match> = when {
        query.isBlank() -> emptyList()
        !byDescription -> Library.searchWords(entries, query, place)
        else -> Library.aboveThreshold(ranked, shownThreshold)
    }
    val matchMillis = (System.nanoTime() - searchStart) / 1_000_000
    // Temps de la recherche, affiché sous le compteur de résultats.
    val timingLine = when {
        query.isBlank() || (byDescription && queryVector == null) -> ""
        !byDescription -> "Recherche par mots : $matchMillis ms"
        else -> "Lecture de la description : ${encodeMillis ?: 0} ms · classement : $matchMillis ms"
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

        if (byDescription && queryVector != null) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Précision", style = MaterialTheme.typography.labelLarge)
                    Text("$aboveNow image(s) au-dessus", style = MaterialTheme.typography.labelLarge)
                }
                Slider(
                    value = threshold,
                    onValueChange = { threshold = it },
                    valueRange = 0.03f..0.15f,
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Plus de résultats", style = MaterialTheme.typography.labelSmall)
                    Text("Plus strict", style = MaterialTheme.typography.labelSmall)
                }
            }
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
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(timingLine, style = MaterialTheme.typography.labelSmall)
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Text(
                        if (byDescription) "${matches.size} image(s) au-dessus du seuil" else "${matches.size} résultat(s)",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                items(matches, key = { it.entry.id }) { match ->
                    ResultTile(
                        match = match,
                        selected = selectedId == match.entry.id,
                        onTap = { selectedId = if (selectedId == match.entry.id) null else match.entry.id },
                        onLongPress = { openInGallery(context, match.entry.source) },
                    )
                }
            }
        }
        // Encart du bas : seulement quand une photo est sélectionnée par un appui.
        val selectedMatch = matches.firstOrNull { it.entry.id == selectedId }
        if (selectedMatch != null) {
            Spacer(Modifier.height(8.dp))
            InfoPanel(
                match = selectedMatch,
                query = query,
                byDescription = byDescription,
                onClose = { selectedId = null },
                onOpen = { openInGallery(context, selectedMatch.entry.source) },
                onCopy = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("nom de la photo", selectedMatch.entry.name))
                    Toast.makeText(context, "Nom copié", Toast.LENGTH_SHORT).show()
                },
            )
        }
    }
}

/**
 * Une vignette : photo seule, score et date en petit sur la photo. Un appui court la sélectionne
 * (bordure bleue à l'extérieur de la photo). Un appui maintenu trace la bordure pendant une seconde,
 * puis ouvre la galerie.
 */
@Composable
private fun ResultTile(match: Match, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val entry = match.entry
    val ring = remember { Animatable(0f) }
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(pressing) {
        if (pressing) ring.animateTo(1f, tween(LONG_PRESS_MS.toInt())) else ring.snapTo(0f)
    }
    val primary = MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .padding(4.dp)
            .then(
                if (selected) Modifier.border(2.dp, primary, RoundedCornerShape(16.dp)) else Modifier,
            )
            .pointerInput(entry.id) {
                detectTapGestures(onPress = {
                    pressing = true
                    val result = withTimeoutOrNull(LONG_PRESS_MS) { tryAwaitRelease() }
                    if (result == null) onLongPress() else if (result) onTap()
                    pressing = false
                })
            },
    ) {
        GridThumb(
            location = entry.source.ifBlank { entry.path },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            summaryLine(match),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(6.dp),
        )
        if (!selected && ring.value > 0f) {
            Canvas(modifier = Modifier.matchParentSize()) {
                val w = size.width
                val h = size.height
                val perimeter = 2f * (w + h)
                val path = Path().apply { addRect(Rect(0f, 0f, w, h)) }
                drawPath(
                    path = path,
                    color = primary,
                    style = Stroke(
                        width = 2.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(maxOf(0.01f, perimeter * ring.value), perimeter * 2f),
                        ),
                    ),
                )
            }
        }
    }
}

/** Vignette carrée d'une photo, décodée à petite taille. */
@Composable
private fun GridThumb(location: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, location) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, location)?.asImageBitmap() }
    }
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
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

@Composable
private fun ResultCard(match: Match, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val entry = match.entry
    val shape = RoundedCornerShape(14.dp)
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.border(2.dp, MaterialTheme.colorScheme.primary, shape) else Modifier)
            .pointerInput(entry.id) {
                detectTapGestures(onPress = {
                    // Appui court : sélection. Appui maintenu : ouverture de la galerie.
                    when (withTimeoutOrNull(LONG_PRESS_MS) { tryAwaitRelease() }) {
                        null -> onLongPress()
                        true -> onTap()
                        false -> Unit
                    }
                })
            },
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Thumbnail(entry.source.ifBlank { entry.path })
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    summaryLine(match),
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Score (recherche par description) et date, sur une seule ligne. */
private fun summaryLine(match: Match): String {
    val score = match.score?.let { "%.3f".format(it) }
    val date = match.entry.taken.ifBlank { "date inconnue" }
    return listOfNotNull(score, date).joinToString(" · ")
}

/** Encart du bas : informations sur la photo sélectionnée, avec croix et actions. */
@Composable
private fun InfoPanel(
    match: Match,
    query: String,
    byDescription: Boolean,
    onClose: () -> Unit,
    onOpen: () -> Unit,
    onCopy: () -> Unit,
) {
    val entry = match.entry
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Text("✕")
                }
            }
            Text(
                if (byDescription) "Trouvée grâce à la description : ${"%.3f".format(match.score ?: 0f)}"
                else "Trouvée grâce au mot : « $query »",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(onClick = onOpen) { Text("Ouvrir dans la galerie") }
                TextButton(onClick = onCopy) { Text("Copier le nom") }
            }
        }
    }
}

@Composable
private fun Thumbnail(location: String) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, location) {
        value = withContext(Dispatchers.IO) { decodeThumbnail(context, location)?.asImageBitmap() }
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

/** Durée d'un appui pour qu'il compte comme un appui maintenu (ouverture de la galerie). */
private const val LONG_PRESS_MS = 1000L

/** Ouvre la photo d'origine dans l'application de galerie ou de bibliothèque du téléphone. */
private fun openInGallery(context: Context, source: String) {
    if (source.isBlank()) {
        Toast.makeText(context, "Photo d'origine introuvable.", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(source), "image/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Aucune application pour afficher cette photo.", Toast.LENGTH_SHORT).show()
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
private fun decodeThumbnail(context: Context, location: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(context, location)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 160 && bounds.outHeight / (sample * 2) >= 160) {
        sample *= 2
    }
    return openImage(context, location)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** Une photo d'origine (adresse content://) ou un fichier de l'application. */
private fun openImage(context: Context, location: String): java.io.InputStream? =
    if (location.startsWith("content:")) {
        context.contentResolver.openInputStream(Uri.parse(location))
    } else {
        java.io.File(location).takeIf { it.exists() }?.inputStream()
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
