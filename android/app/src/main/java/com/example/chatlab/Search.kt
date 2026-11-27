package com.example.chatlab

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Debug
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.lazy.grid.LazyGridState
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius

/**
 * The state of the search, shared between the search screen and the Filtres page so that the
 * filters chosen there apply as soon as we come back.
 */
class SearchUiState {
    var query by mutableStateOf("")
    var filters by mutableStateOf(Filters())
    var shownExtra by mutableIntStateOf(0)
    /** How many photos per row: 1 to 4. */
    var perRow by mutableIntStateOf(PerRow.DEFAULT)
    /** Position, in the photos less close, of the last batch added (to highlight it). */
    var newFrom by mutableIntStateOf(-1)
    var ranked by mutableStateOf(emptyList<Match>())
    /** The filter sheet, which rises over the search. */
    var sheetOpen by mutableStateOf(false)
    /** Set when an analysis has just finished, until the user opens it. */
    var analysisDone by mutableStateOf(false)
    /** The list position and the last results, kept while the other tab is open. */
    val gridState = LazyGridState()
    var lastOutcome: SearchOutcome = SearchOutcome.EMPTY

    fun reset() {
        query = ""
        filters = Filters()
        sheetOpen = false
        shownExtra = 0
        newFrom = -1
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
    val time = remember(query, state.filters) { When(System.currentTimeMillis()) }
    // The place is chosen, but does not filter the results yet: see Filters.withoutPlace.
    val resultFilters = state.filters.withoutPlace()
    // A date typed in the box ("hier", "26 novembre 2026") filters the results; the rest is what gets compared.
    val typed = remember(query, time) { TypedDates.parse(query, time) }
    val gridState = state.gridState

    // A new search or other filters start again from the beginning: neither photos less close, nor selection.
    LaunchedEffect(query, state.filters) {
        state.shownExtra = 0
        state.newFrom = -1
    }

    // The sentence becomes a vector once typing pauses, for the similar photos.
    LaunchedEffect(typed.rest) {
        queryVector = null
        modelError = null
        if (typed.rest.isNotBlank()) {
            SearchStages.begin()
            SearchStages.mark(context, "typed")
            delay(400)
            SearchStages.mark(context, "delay.end")
            try {
                queryVector = withContext(Dispatchers.Default) {
                    SearchStages.mark(context, "encode.start")
                    TextModels.vectorFor(context, typed.rest).also { SearchStages.mark(context, "encode.end") }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                modelError = error.message ?: error.javaClass.simpleName
            }
        }
    }
    // The vector whose ranking is in state.ranked: until it matches the current vector, the search is still running.
    var rankedFor by remember { mutableStateOf<FloatArray?>(null) }
    // The photos already scored for rankedFor. While the sentence stays the same, a change of the library
    // scores only the new photos: the search does not start again, so it does not flicker during an analysis.
    var rankedIds by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(queryVector, entries) {
        val vector = queryVector
        val sameQuery = vector != null && rankedFor === vector
        if (!sameQuery) rankedFor = null
        if (vector == null) {
            state.ranked = emptyList()
            rankedIds = emptySet()
            return@LaunchedEffect
        }
        val fresh = IncrementalRanking.photosToScore(entries, rankedIds, sameQuery)
        if (sameQuery && fresh.isEmpty()) return@LaunchedEffect
        // The timing goes to the journal from the background thread: writing it on the main thread would be a violation.
        val found = withContext(Dispatchers.Default) {
            val started = System.currentTimeMillis()
            SearchStages.mark(context, "rank.start")
            val scored = Library.rankDescription(fresh, vector, Place.ALL, null)
            SearchStages.mark(context, "rank.end")
            Diagnostics.event(
                context,
                "searchRank",
                JSONObject()
                    .put("ms", System.currentTimeMillis() - started)
                    .put("photos", fresh.size)
                    .put("found", scored.size)
                    .put("incremental", sameQuery)
                    // Memory at each search: the native part (models) and the Java part (the library).
                    .put("nativeMb", android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
                    .put("javaMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / (1024 * 1024))
                    // Thumbnails on screen: their pixels, in native memory, and how many there are.
                    .put("thumbMb", ThumbStats.megabytes())
                    .put("thumbs", ThumbStats.count()),
            )
            scored
        }
        state.ranked = if (sameQuery) IncrementalRanking.merge(state.ranked, found) else found
        rankedIds = if (sameQuery) rankedIds + fresh.map { it.id } else entries.map { it.id }.toSet()
        rankedFor = vector
    }
    val pending = rankingPending(typed.rest.isBlank(), modelError != null, queryVector, rankedFor)
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
    val searchTyped = remember(searchQuery, time) { TypedDates.parse(searchQuery, time) }
    val searchFilters = resultFilters.withTypedDate(searchTyped.range)
    val outcome by produceState(state.lastOutcome, entries, searchTyped.rest, state.ranked, searchFilters, state.shownExtra, time) {
        value = withContext(Dispatchers.Default) {
            SearchStages.mark(context, "outcome.start")
            SearchResults.compute(entries, searchTyped.rest, state.ranked, searchFilters, state.shownExtra, time) {
                SearchStages.mark(context, it)
            }.also { SearchStages.mark(context, "outcome.end") }
        }
        state.lastOutcome = value
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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
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
        Spacer(Modifier.height(10.dp))

        // Recent searches, then the filters applied (on lines, never scrolled), then the results.
        val chips = state.filters.chips(time)
        if (query.isBlank() && entries.isNotEmpty()) {
            Recents(
                recents,
                onPick = { state.query = it },
                onRemove = { recents = RecentStore.remove(context, it) },
            )
            Spacer(Modifier.height(18.dp))
        }
        if (chips.isNotEmpty()) {
            Text("Filtres", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
            Spacer(Modifier.height(8.dp))
            ActiveFilterPills(chips, onRemove = { state.filters = it.without })
            Spacer(Modifier.height(18.dp))
        }

        // The photo opened by a tap, shown in the application itself.
        var viewing by remember { mutableStateOf("") }
        if (viewing.isNotBlank()) {
            PhotoViewer(
                source = viewing,
                onClose = { viewing = "" },
                onOpenExternal = { openInGallery(context, viewing) },
            )
        }
        if (perRowControlShown(query, hasResults = !outcome.isEmpty, loading = loading, ranking = waiting == Waiting.RANKING)) {
            PerRowControl(selected = state.perRow, onSelect = { state.perRow = PerRow.clamp(it) })
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
                columns = GridCells.Fixed(state.perRow),
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
                        is ResultItem.Separator -> TextDivider(item.text)
                        is ResultItem.Tile -> {
                            ResultTile(
                                modifier = Modifier.animateItem(placementSpec = tween(1200, easing = FastOutSlowInEasing)),
                                match = item.match,
                                isNew = item.isNew,
                                highlightKey = state.shownExtra,
                                onTap = { viewing = item.match.entry.source.ifBlank { item.match.entry.path } },
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
                // A fixed height: the "✕" that appears while typing must not move the underline.
                .height(48.dp),
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Recents(recents: List<String>, onPick: (String) -> Unit, onRemove: (String) -> Unit) {
    if (recents.isEmpty()) return
    val touchSlop = LocalViewConfiguration.current.touchSlop
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel("Récentes")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            recents.forEach { recent ->
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(18.dp))
                        .border(1.dp, Palette.Line, RoundedCornerShape(18.dp))
                        .padding(start = 14.dp, end = 6.dp, top = 7.dp, bottom = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        recent,
                        fontSize = 14.sp,
                        color = Palette.Ink,
                        // A tap searches it; a hold of the same length as on a photo removes it, here and on the phone.
                        modifier = Modifier.tapOrHold(recent, touchSlop, onTap = { onPick(recent) }, onHold = { onRemove(recent) }),
                    )
                    Spacer(Modifier.width(8.dp))
                    // The cross on the right removes the search at once, as the hold does.
                    Box(
                        modifier = Modifier
                            .size(18.dp)
                            .clip(CircleShape)
                            .background(Palette.Field)
                            .clickable { onRemove(recent) },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("×", fontSize = 12.sp, color = Palette.Muted)
                    }
                }
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

/**
 * A similar photo: a square thumbnail. A tap opens it. Held for two seconds, its name, folder and date
 * appear over its bottom edge; a tap on them copies the name, so that a search with that name finds the photo.
 */
@Composable
private fun ResultTile(
    modifier: Modifier = Modifier,
    match: Match,
    isNew: Boolean,
    highlightKey: Int,
    onTap: () -> Unit,
) {
    val entry = match.entry
    val context = LocalContext.current
    val touchSlop = LocalViewConfiguration.current.touchSlop
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
    // The information stays until the tile leaves the screen: a short flicker, then its lines one by one.
    var infoShown by remember(entry.id) { mutableStateOf(false) }
    var tileSize by remember(entry.id) { mutableStateOf(IntSize.Zero) }
    var infoHeight by remember(entry.id) { mutableIntStateOf(0) }
    val flash = remember(entry.id) { Animatable(0f) }
    val reveal = remember(entry.id) { Animatable(0f) }
    LaunchedEffect(infoShown) {
        if (infoShown) {
            repeat(2) {
                flash.snapTo(0.5f)
                flash.animateTo(0f, tween(150))
            }
            reveal.snapTo(0f)
            reveal.animateTo(3f, tween(900, easing = LinearEasing))
        }
    }
    Box(
        modifier = modifier
            .glow(glow.value)
            .semantics { contentDescription = entry.name }
            .onSizeChanged { tileSize = it }
            .pointerInput(entry.id, touchSlop) {
                // Reads the finger itself, before any layer above takes it: a quick release is a tap, a move is a
                // scroll, and a finger still down after HOLD_MS shows the information.
                awaitPointerEventScope {
                    while (true) {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        val onInfo = infoShown && down.position.y >= tileSize.height - infoHeight
                        when (withTimeoutOrNull(HOLD_MS) { trackPress(down, touchSlop) }) {
                            PressOutcome.TAP -> if (!onInfo) onTap()
                            PressOutcome.MOVED -> Unit
                            null -> {
                                infoShown = true
                                waitForRelease(down.id)
                            }
                        }
                    }
                }
            },
    ) {
        GridThumb(
            location = entry.source.ifBlank { entry.path },
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(14.dp)),
        )
        if (infoShown) {
            Box(Modifier.matchParentSize().background(Color.White.copy(alpha = flash.value)))
            ResultInfo(
                entry = entry,
                reveal = reveal.value,
                onHeight = { infoHeight = it },
                onCopy = { copyName(context, entry.name) },
            )
        }
    }
}

/** The name, folder and date of a photo, over its bottom edge: each line appears after the previous one. */
@Composable
private fun BoxScope.ResultInfo(entry: LibraryEntry, reveal: Float, onHeight: (Int) -> Unit, onCopy: () -> Unit) {
    val lines = listOfNotNull(
        entry.name,
        entry.folder.takeIf { it.isNotBlank() },
        Dates.long(entry.taken),
    )
    Column(
        modifier = Modifier
            .align(Alignment.BottomStart)
            .fillMaxWidth()
            .onSizeChanged { onHeight(it.height) }
            .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9111827))))
            .clickable(onClick = onCopy)
            .padding(start = 8.dp, end = 8.dp, top = 28.dp, bottom = 8.dp),
    ) {
        lines.forEachIndexed { index, line ->
            val alpha = (reveal - index).coerceIn(0f, 1f)
            Text(
                line,
                fontSize = 11.sp,
                fontWeight = if (index == 0) FontWeight.SemiBold else FontWeight.Normal,
                color = Color.White.copy(alpha = alpha),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Copies the file name to the clipboard, so that it can be used elsewhere or searched again. */
private fun copyName(context: Context, name: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Nom du fichier", name))
    Toast.makeText(context, "Nom copié : $name", Toast.LENGTH_SHORT).show()
}

@Composable
fun GridThumb(location: String, modifier: Modifier) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, location) {
        value = withContext(Dispatchers.IO) {
            try {
                Diagnostics.native(context, "thumb") { ThumbStats.decoding { decodeThumbnail(context, location) } }?.asImageBitmap()
                    ?.also { ThumbStats.decoded(it.asAndroidBitmap().allocationByteCount.toLong()) }
            } catch (e: Exception) {
                null
            }
        }
    }
    // The pixels of a thumbnail are counted while it is shown, and removed when it leaves the screen.
    DisposableEffect(image) {
        val bytes = image?.asAndroidBitmap()?.allocationByteCount?.toLong()
        if (bytes != null) ThumbStats.show(bytes)
        onDispose { if (bytes != null) ThumbStats.hide(bytes) }
    }
    Box(modifier = modifier.background(Palette.Field), contentAlignment = Alignment.Center) {
        image?.let {
            Image(bitmap = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}


/** Opens the original photo in the gallery application of the phone. */
private fun openInGallery(context: Context, source: String) {
    if (source.isBlank()) {
        Toast.makeText(context, "Photo d'origine introuvable.", Toast.LENGTH_SHORT).show()
        return
    }
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(source), imageMimeOf(source))
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

/** Pixels of the thumbnails currently shown, in bytes, and their number. For the journal only. */
object ThumbStats {
    private val bytes = AtomicLong()
    private val count = AtomicInteger()
    private val running = AtomicInteger()
    private val peak = AtomicInteger()
    private val decodedBytes = AtomicLong()

    /** Runs one decoding, counting how many run at the same time (peak) and the bytes decoded in all. */
    fun <T> decoding(block: () -> T): T {
        val now = running.incrementAndGet()
        peak.accumulateAndGet(now) { a, b -> maxOf(a, b) }
        try {
            return block()
        } finally {
            running.decrementAndGet()
        }
    }

    fun decoded(size: Long) {
        decodedBytes.addAndGet(size)
    }

    fun running(): Int = running.get()

    fun peak(): Int = peak.get()

    fun decodedMb(): Long = decodedBytes.get() / (1024 * 1024)

    fun show(size: Long) {
        bytes.addAndGet(size)
        count.incrementAndGet()
    }

    fun hide(size: Long) {
        bytes.addAndGet(-size)
        count.decrementAndGet()
    }

    fun megabytes(): Long = bytes.get() / (1024 * 1024)

    fun count(): Int = count.get()
}

/**
 * Marks each step of a search in the journal ("searchStage"): the native and Java memory at that moment,
 * and the time since the search was typed. The steps are: typed, delay.end, encode.start/end (the model, or
 * the cache), rank.start/end (the ranking over the library), outcome.start/end (filters, candidates, thresholds).
 */
object SearchStages {
    @Volatile private var startedAt = 0L

    fun begin() {
        startedAt = System.currentTimeMillis()
    }

    fun mark(context: Context, stage: String) {
        val mb = 1024L * 1024L
        Diagnostics.event(
            context,
            "searchStage",
            JSONObject()
                .put("stage", stage)
                .put("sinceMs", System.currentTimeMillis() - startedAt)
                .put("nativeMb", Debug.getNativeHeapAllocatedSize() / mb)
                .put("javaMb", (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) / mb),
        )
    }
}

/** A photo full screen in the application: a back button at the top, the photo fitted on black. */
@Composable
private fun PhotoViewer(source: String, onClose: () -> Unit, onOpenExternal: () -> Unit) {
    val context = LocalContext.current
    val image by produceState<ImageBitmap?>(initialValue = null, source) {
        value = withContext(Dispatchers.IO) { decodeLarge(context, source)?.asImageBitmap() }
    }
    // The zoom and the position of the photo: from 1 (the normal size) up to 4 times.
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // The photo stays inside its frame: no empty space appears around it when it is zoomed.
    fun keepInside(move: Offset, zoom: Float): Offset {
        val maxX = viewSize.width * (zoom - 1f) / 2f
        val maxY = viewSize.height * (zoom - 1f) / 2f
        return Offset(move.x.coerceIn(-maxX, maxX), move.y.coerceIn(-maxY, maxY))
    }
    // Pinch to zoom, and drag to move the zoomed photo.
    val transform = rememberTransformableState { zoomChange, pan, _ ->
        val next = (scale * zoomChange).coerceIn(1f, 4f)
        scale = next
        offset = if (next <= 1f) Offset.Zero else keepInside(offset + pan, next)
    }
    // The system back gesture closes the dialog as well (onDismissRequest).
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            image?.let {
                Image(
                    bitmap = it,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { viewSize = it }
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            translationX = offset.x
                            translationY = offset.y
                        }
                        .transformable(state = transform)
                        // A double tap zooms to the place touched, or back to the normal size.
                        .pointerInput(Unit) {
                            detectTapGestures(onDoubleTap = { point ->
                                if (scale > 1f) {
                                    scale = 1f
                                    offset = Offset.Zero
                                } else {
                                    val center = Offset(size.width / 2f, size.height / 2f)
                                    scale = 2.5f
                                    offset = keepInside(-(point - center) * 1.5f, 2.5f)
                                }
                            })
                        },
                )
            }
            if (image == null) CircularProgressIndicator(modifier = Modifier.align(Alignment.Center), color = Color.White)
            Text(
                "‹ Retour",
                color = Color.White,
                fontSize = 16.sp,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .clickable(onClick = onClose)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
            Text(
                "Ouvrir dans la galerie",
                color = Color.White,
                fontSize = 14.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(16.dp)
                    .clickable(onClick = onOpenExternal),
            )
        }
    }
}

/** The photo at a size where its longest side is about [maxSide] pixels: enough for the screen, not more. */
private fun decodeLarge(context: Context, location: String, maxSide: Int = 1600): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    openImage(context, location)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
    return openImage(context, location)?.use {
        BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}

/** "Photos par ligne": four choices, the chosen one in navy. The colour and the grid icon change smoothly. */
@Composable
private fun PerRowControl(selected: Int, onSelect: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text("Photos par ligne", fontSize = 13.sp, color = Palette.Muted)
        Row(
            modifier = Modifier
                .border(1.dp, Color(0xFFE4E7EC), CircleShape)
                .background(Color(0xFFF7F8FA), CircleShape)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            listOf(1, 2, 3, 4).forEach { count ->
                val on = count == selected
                val background by animateColorAsState(if (on) Palette.Accent else Color.White, tween(600), label = "background")
                val foreground by animateColorAsState(if (on) Color.White else Palette.Accent, tween(600), label = "foreground")
                Box(
                    modifier = Modifier
                        .size(width = 44.dp, height = 34.dp)
                        .clip(CircleShape)
                        .background(background)
                        .clickable { onSelect(count) },
                    contentAlignment = Alignment.Center,
                ) {
                    GridIcon(count, foreground)
                }
            }
        }
    }
}

/** A square of squares: 1 big square, or a grid of 2 x 2, 3 x 3, 4 x 4 small ones. */
@Composable
private fun GridIcon(count: Int, color: Color) {
    Canvas(modifier = Modifier.size(20.dp)) {
        val side = size.minDimension
        if (count == 1) {
            drawRoundRect(color, topLeft = Offset(side * 0.15f, side * 0.15f), size = Size(side * 0.7f, side * 0.7f), cornerRadius = CornerRadius(side * 0.12f))
            return@Canvas
        }
        val gap = side * 0.06f
        val cell = (side - gap * (count - 1)) / count
        for (row in 0 until count) for (col in 0 until count) {
            drawRoundRect(
                color,
                topLeft = Offset(col * (cell + gap), row * (cell + gap)),
                size = Size(cell, cell),
                cornerRadius = CornerRadius(cell * 0.15f),
            )
        }
    }
}
