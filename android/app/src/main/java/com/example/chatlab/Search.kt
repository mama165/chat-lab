package com.example.chatlab

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.chatlab.ui.FilterTag
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import com.example.chatlab.ui.SectionLabel
import com.example.chatlab.ui.Segmented
import com.example.chatlab.ui.TabTitle
import com.example.chatlab.ui.TextDivider
import com.example.chatlab.ui.glow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject

/**
 * The state of the search, shared between the search screen and the Filtres page so that the
 * filters chosen there apply as soon as we come back.
 */
class SearchUiState {
    var query by mutableStateOf("")
    var filters by mutableStateOf(Filters())
    var shownExtra by mutableIntStateOf(0)
    /** Position, in the photos less close, of the last batch added (to highlight it). */
    var newFrom by mutableIntStateOf(-1)
    var selected by mutableStateOf(emptySet<String>())
    var ranked by mutableStateOf(emptyList<Match>())
    /** The filter sheet, which rises over the search. */
    var sheetOpen by mutableStateOf(false)
    /** Set when an analysis has just finished, until the user opens it. */
    var analysisDone by mutableStateOf(false)

    fun reset() {
        query = ""
        filters = Filters()
        sheetOpen = false
        shownExtra = 0
        newFrom = -1
        selected = emptySet()
        ranked = emptyList()
    }
}

/** Search tab: title and gear, field and filters, then the results. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SearchScreen(
    state: SearchUiState,
    entries: List<LibraryEntry>,
    loading: Boolean,
    removalsVersion: Int,
    onRemovalsChanged: () -> Unit,
    onSettings: () -> Unit,
    onFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val focus = LocalFocusManager.current
    val query = state.query
    val queryKey = query.trim().lowercase()
    var queryVector by remember { mutableStateOf<FloatArray?>(null) }
    var modelError by remember { mutableStateOf<String?>(null) }
    var recents by remember { mutableStateOf(RecentStore.read(context)) }
    val time = remember(query, state.filters, entries) { When(System.currentTimeMillis()) }
    // The place is chosen, but does not filter the results yet: see Filters.withoutPlace.
    val resultFilters = state.filters.withoutPlace()
    val gridState = rememberLazyGridState()

    // A new search or other filters start again from the beginning: neither photos less close, nor selection.
    LaunchedEffect(query, state.filters) {
        state.shownExtra = 0
        state.newFrom = -1
        state.selected = emptySet()
    }

    // The sentence becomes a vector once typing pauses, for the similar photos.
    LaunchedEffect(query) {
        queryVector = null
        modelError = null
        if (query.isNotBlank()) {
            delay(400)
            try {
                queryVector = withContext(Dispatchers.Default) { TextModels.vectorFor(context, query) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                modelError = error.message ?: error.javaClass.simpleName
            }
        }
    }
    // The vector whose ranking is in state.ranked: until it matches the current vector, the search is still running.
    var rankedFor by remember { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(queryVector, entries) {
        val vector = queryVector
        rankedFor = null
        if (vector == null) {
            state.ranked = emptyList()
            return@LaunchedEffect
        }
        // The timing goes to the journal from the background thread: writing it on the main thread would be a violation.
        state.ranked = withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            val found = Library.rankDescription(entries, vector, Place.ALL, null)
            Diagnostics.event(
                context,
                "searchRank",
                JSONObject()
                    .put("ms", System.currentTimeMillis() - started)
                    .put("photos", entries.size)
                    .put("found", found.size)
                    // Memory at each search: the native part (models) and the Java part (the library).
                    .put("nativeMb", android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
                    .put("javaMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024)),
            )
            found
        }
        rankedFor = vector
    }
    val pending = rankingPending(query.isBlank(), modelError != null, queryVector, rankedFor)
    var timedOut by remember { mutableStateOf(false) }
    LaunchedEffect(pending, queryKey) {
        timedOut = false
        if (pending) {
            delay(SEARCH_LIMIT_MS)
            timedOut = true
            withContext(Dispatchers.IO) {
                Diagnostics.event(context, "searchTimeout", JSONObject().put("limitMs", SEARCH_LIMIT_MS).put("photos", entries.size))
            }
        }
    }
    val waiting = waitingFor(pending, timedOut)

    // The results follow the typing once it pauses, and they are computed off the main thread: the letters
    // appear at once, and typing does not wait for the whole library to be searched.
    var searchQuery by remember { mutableStateOf(query) }
    LaunchedEffect(query) {
        delay(250)
        searchQuery = query
    }
    val searchKey = searchQuery.trim().lowercase()
    val removed by produceState<Set<String>>(initialValue = emptySet(), searchKey, removalsVersion) {
        value = withContext(Dispatchers.IO) { Removals.sourcesFor(context, searchKey) }
    }
    val outcome by produceState(SearchOutcome.EMPTY, entries, searchQuery, state.ranked, resultFilters, removed, state.shownExtra, time) {
        value = withContext(Dispatchers.Default) {
            SearchResults.compute(entries, searchQuery, state.ranked, resultFilters, removed, state.shownExtra, time)
        }
    }
    val rows = remember(outcome, state.newFrom, searchQuery) { resultItems(outcome, searchQuery, state.newFrom) }
    val latestOutcome by rememberUpdatedState(outcome)

    // A search that gives results joins the recent searches after a pause ofrappe.
    LaunchedEffect(query) {
        if (query.isBlank()) return@LaunchedEffect
        delay(1500)
        if (!latestOutcome.isEmpty) recents = RecentStore.add(context, query)
    }

    // After "Voir des photos moins proches", the list slides down to the new photos.
    LaunchedEffect(state.shownExtra) {
        if (state.newFrom < 0) return@LaunchedEffect
        val index = rows.indexOfFirst { it is ResultItem.Tile && it.isNew }
        if (index >= 0) gridState.animateScrollToItem(index)
    }

    BackHandler(enabled = state.selected.isNotEmpty()) { state.selected = emptySet() }

    val selecting = state.selected.isNotEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        if (selecting) {
            SelectionBar(
                count = state.selected.size,
                query = query,
                onCancel = { state.selected = emptySet() },
                onRemove = {
                    Removals.add(context, queryKey, state.selected)
                    state.selected = emptySet()
                    onRemovalsChanged()
                },
            )
        } else {
            TabTitle(
                "Rechercher",
                onSettings = onSettings,
                onFilters = onFilters,
                filtersCount = state.filters.activeCount,
                subtitle = null,
            )
            Spacer(Modifier.height(12.dp))
            SearchField(
                value = query,
                onChange = { state.query = it },
                onSearch = {
                    focus.clearFocus()
                    if (!outcome.isEmpty) recents = RecentStore.add(context, query)
                },
            )
        }
        Spacer(Modifier.height(10.dp))

        // Recent searches, then the filters applied (on lines, never scrolled), then the results.
        val chips = state.filters.chips(time)
        if (query.isBlank() && entries.isNotEmpty() && !selecting) {
            Recents(recents, onPick = { state.query = it })
            Spacer(Modifier.height(18.dp))
        }
        if (chips.isNotEmpty() && !selecting) {
            Text("Filtres", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            ActiveFilterPills(chips, onRemove = { state.filters = it.without })
            Spacer(Modifier.height(18.dp))
        }

        when {
            loading -> Loading("Veuillez patienter, la bibliothèque se charge…")
            entries.isEmpty() -> Message(
                "Aucune photo analysée pour l'instant.",
                "Ouvrez l'onglet Photos pour lancer l'analyse.",
            )
            query.isBlank() -> Spacer(Modifier.weight(1f))
            waiting == Waiting.RANKING -> Searching()
            waiting == Waiting.TIMED_OUT -> Message("Recherche trop longue, essaie encore.", "Ajoutez un mot, ou relancez la recherche dans un instant.")
            outcome.isEmpty && chips.isNotEmpty() -> Message(
                "Aucune photo ne correspond à « $query ».",
                "Essayez de retirer un filtre.",
            )
            outcome.isEmpty && modelError != null -> Message(
                "Aucune photo ne correspond à « $query ».",
                "Recherche par description indisponible : $modelError",
            )
            outcome.isEmpty -> Message("Aucune photo ne correspond à « $query ».", "Essayez d'autres mots.")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = gridState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = rows.size,
                    key = { rows[it].key },
                    span = { GridItemSpan(if (rows[it] is ResultItem.Tile) 1 else maxLineSpan) },
                ) { index ->
                    when (val item = rows[index]) {
                        is ResultItem.Label -> SectionLabel(item.text, Modifier.padding(top = 6.dp))
                        is ResultItem.Line -> Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
                        is ResultItem.Separator -> TextDivider(item.text)
                        is ResultItem.Word -> {
                            val key = SearchResults.keyOf(item.match)
                            WordRow(
                                match = item.match,
                                query = query,
                                time = time,
                                selected = key in state.selected,
                                onTap = { state.selected = toggled(state.selected, key) },
                                onLongPress = { openInGallery(context, item.match.entry.source) },
                            )
                        }
                        is ResultItem.Tile -> {
                            val key = SearchResults.keyOf(item.match)
                            ResultTile(
                                match = item.match,
                                isNew = item.isNew,
                                highlightKey = state.shownExtra,
                                selected = key in state.selected,
                                onTap = { state.selected = toggled(state.selected, key) },
                                onLongPress = { openInGallery(context, item.match.entry.source) },
                            )
                        }
                        is ResultItem.More -> Text(
                            item.text,
                            fontSize = 14.sp,
                            color = Palette.Accent,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    state.newFrom = outcome.extra.size
                                    state.shownExtra = SearchResults.nextShown(outcome)
                                }
                                .padding(vertical = 12.dp),
                        )
                        is ResultItem.End -> Column(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(item.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, textAlign = TextAlign.Center)
                            Text(item.detail, fontSize = 13.sp, color = Palette.Muted, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onChange: (String) -> Unit,
    onSearch: () -> Unit,
) {
    // As in the design: a bare field with a navy underline, and a helper line under it.
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .drawBehind {
                    val line = 1.5.dp.toPx()
                    drawLine(Palette.Accent, Offset(0f, size.height), Offset(size.width, size.height), strokeWidth = line)
                }
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(painterResource(R.drawable.ic_loupe), contentDescription = null, tint = Palette.Muted, modifier = Modifier.size(20.dp))
            Box(modifier = Modifier.weight(1f)) {
                if (value.isEmpty()) {
                    Text(
                        "Un mot, ou ce que montre la photo",
                        fontSize = 17.sp,
                        color = Palette.Muted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = Palette.Ink),
                    cursorBrush = SolidColor(Palette.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (value.isNotEmpty()) {
                Text(
                    "✕",
                    fontSize = 14.sp,
                    color = Palette.Muted,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable { onChange("") }
                        .semantics { contentDescription = "Effacer la recherche" }
                        .padding(4.dp),
                )
            }
        }
        Text(
            "Par exemple « facture », « wifi » ou « une plage ».",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
    }
}

/** When selecting, the top bar replaces the field: number chosen, cancel, remove. */
@Composable
private fun SelectionBar(count: Int, query: String, onCancel: () -> Unit, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Palette.Soft)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "✕",
            fontSize = 16.sp,
            color = Palette.Accent,
            modifier = Modifier
                .clip(CircleShape)
                .clickable(onClick = onCancel)
                .semantics { contentDescription = "Annuler la sélection" }
                .padding(8.dp),
        )
        Text(
            if (count == 1) "1 sélectionnée" else "$count sélectionnées",
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            color = Palette.Accent,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Retirer de « $query »",
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(Palette.Accent)
                .clickable(onClick = onRemove)
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
    Text(
        "Appui court pour choisir, appui long pour ouvrir",
        fontSize = 12.sp,
        color = Palette.Muted,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Recents(recents: List<String>, onPick: (String) -> Unit) {
    if (recents.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Récentes")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { recent ->
                Text(
                    recent,
                    fontSize = 14.sp,
                    color = Palette.Ink,
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
                        .clickable { onPick(recent) }
                        .padding(horizontal = 14.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** While the library is read: the gauge turns, with a short line of text. */
@Composable
private fun Loading(text: String) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GaugeRing(fraction = null, size = 40.dp)
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}

/** While the photos are ranked: the gauge turns, in place of the results. */
@Composable
private fun Searching() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        GaugeRing(fraction = null, size = 40.dp)
        Text("Recherche en cours…", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
    }
}

@Composable
private fun Message(title: String, detail: String?) {
    Column(modifier = Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
        if (detail != null) Text(detail, fontSize = 13.sp, color = Palette.Muted)
    }
}

/** "Non classifiées" page: the removed photos, per search, with the date and Rétablir. */
@Composable
fun RemovedPage(onBack: () -> Unit, onChanged: () -> Unit) {
    val context = LocalContext.current
    var items by remember { mutableStateOf(Removals.all(context)) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(Modifier.height(4.dp))
        PageHeader("Non classifiées", onBack = onBack)
        val searches = items.map { it.query }.distinct().size
        Text(
            if (items.isEmpty()) "Aucune photo retirée de vos recherches."
            else "${Formats.photos(items.size).replaceFirstChar { it.uppercase() }} retirée${if (items.size > 1) "s" else ""}, dans $searches recherche${if (searches > 1) "s" else ""}",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        items.groupBy { it.query }.forEach { (query, group) ->
            Text("« $query » (${group.size})", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
            group.forEach { removal ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Palette.Field)
                        .padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GridThumb(removal.source, Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (removal.date.isBlank()) "Retirée" else "Retirée le ${removal.date}",
                        fontSize = 12.sp,
                        color = Palette.Muted,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "Rétablir",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(Color.White)
                            .clickable {
                                Removals.restore(context, removal.query, removal.source)
                                items = Removals.all(context)
                                onChanged()
                            }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                    )
                }
            }
        }
        if (items.isNotEmpty()) {
            Text(
                "Rétablir fait disparaître la photo de cette liste et la remet tout de suite dans la recherche d'origine.",
                fontSize = 11.sp,
                color = Palette.Muted,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

private fun toggled(current: Set<String>, key: String): Set<String> =
    if (key in current) current - key else current + key

/** A match by word: thumbnail, excerpt of the text read with the word highlighted, date. */
@Composable
private fun WordRow(match: Match, query: String, time: When, selected: Boolean, onTap: () -> Unit, onLongPress: () -> Unit) {
    val entry = match.entry
    val key = SearchResults.keyOf(match)
    val excerpt = remember(entry.text, entry.name, query) {
        Highlight.excerpt(entry.text.ifBlank { entry.name }, query)
    }
    val text = buildAnnotatedString {
        var at = 0
        for (mark in excerpt.marks) {
            append(excerpt.text.substring(at, mark.first))
            withStyle(SpanStyle(color = Palette.Accent, fontWeight = FontWeight.Bold)) {
                append(excerpt.text.substring(mark.first, mark.last + 1))
            }
            at = mark.last + 1
        }
        append(excerpt.text.substring(at))
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .pressGesture(key, onTap, onLongPress)
            .semantics { contentDescription = entry.name }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = Modifier.size(52.dp)) {
            GridThumb(
                location = key,
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .alpha(if (selected) 0.45f else 1f),
            )
            if (selected) Check(Modifier.align(Alignment.TopEnd).padding(2.dp), 18)
        }
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(text, fontSize = 14.sp, color = Palette.Ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(Dates.display(entry.takenMs, entry.taken, time), fontSize = 11.sp, color = Palette.Muted)
        }
    }
}

@Composable
private fun Check(modifier: Modifier, size: Int) {
    Box(
        modifier = modifier
            .size(size.dp)
            .clip(CircleShape)
            .background(Color.White)
            .padding(2.dp)
            .clip(CircleShape)
            .background(Palette.Accent),
        contentAlignment = Alignment.Center,
    ) {
        Text("✓", fontSize = (size / 2 + 1).sp, color = Color.White)
    }
}

/** Short tap = selection, long press = opening in the gallery. */
private fun Modifier.pressGesture(key: Any, onTap: () -> Unit, onLongPress: () -> Unit): Modifier =
    this.pointerInput(key) {
        detectTapGestures(onPress = {
            val result = withTimeoutOrNull(LONG_PRESS_MS) { tryAwaitRelease() }
            if (result == null) onLongPress() else if (result) onTap()
        })
    }

/** A similar photo: square thumbnail, tick if chosen, ring during a long press. */
@Composable
private fun ResultTile(
    match: Match,
    isNew: Boolean,
    highlightKey: Int,
    selected: Boolean,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    val entry = match.entry
    val ring = remember { Animatable(0f) }
    var pressing by remember { mutableStateOf(false) }
    LaunchedEffect(pressing) {
        if (pressing) ring.animateTo(1f, tween(LONG_PRESS_MS.toInt())) else ring.snapTo(0f)
    }
    // The photos that have just arrived are briefly outlined.
    val glow = remember { Animatable(0f) }
    LaunchedEffect(isNew, highlightKey) {
        if (isNew) {
            glow.snapTo(1f)
            delay(600)
            glow.animateTo(0f, tween(900))
        } else {
            glow.snapTo(0f)
        }
    }
    val primary = Palette.Accent
    Box(
        modifier = Modifier
            .glow(glow.value)
            .semantics { contentDescription = entry.name }
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
                .clip(RoundedCornerShape(14.dp))
                .alpha(if (selected) 0.45f else 1f),
        )
        if (selected) Check(Modifier.align(Alignment.TopEnd).padding(6.dp), 22)
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
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(maxOf(0.01f, perimeter * ring.value), perimeter * 2f)),
                    ),
                )
            }
        }
    }
}

@Composable
fun GridThumb(location: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, location) {
        value = withContext(Dispatchers.IO) {
            try {
                decodeThumbnail(context, location)?.asImageBitmap()
            } catch (e: Exception) {
                null
            }
        }
    }
    Box(modifier = modifier.background(Palette.Field), contentAlignment = Alignment.Center) {
        image?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

private const val LONG_PRESS_MS = 500L

/** Opens the original photo in the gallery application of the phone. */
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

/** Decodes the image at a small size, so that the list stays smooth. */
private fun decodeThumbnail(context: Context, location: String): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(context, location)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= 200 && bounds.outHeight / (sample * 2) >= 200) {
        sample *= 2
    }
    return openImage(context, location)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** An original photo (content:// address) or a file of the application. */
private fun openImage(context: Context, location: String): java.io.InputStream? =
    if (location.startsWith("content:")) {
        context.contentResolver.openInputStream(Uri.parse(location))
    } else {
        java.io.File(location).takeIf { it.exists() }?.inputStream()
    }
