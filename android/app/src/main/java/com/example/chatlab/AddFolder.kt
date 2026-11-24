package com.example.chatlab

import android.content.Context
import android.os.Environment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The folders with photos to analyse, found once when the page opens. */
private class Pending(val roots: List<FolderNode>, val photos: Map<String, List<String>>)

/**
 * The "+" page, to add a folder to the analysis once the first folders are chosen. It only
 * shows what is left to add: a folder with a photo not analysed yet, at any depth. A folder
 * already added, and everything under it, is not shown. There is no count and no "Fait":
 * a tap adds the folder, and the arrow goes back to the Photos tab.
 */
@Composable
fun AddFolderPage(onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.granted()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) granted = StorageAccess.granted()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    if (!granted) {
        AccessPanel(onOpenSettings = { StorageAccess.openSettings(context) }, onBack = onBack)
        return
    }

    val storage = remember { Environment.getExternalStorageDirectory().absolutePath }
    val pending by produceState<Pending?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { findPending(context, storage) }
    }
    // The folders already added, by path. Taps are added to [justAdded] first, so the row stays
    // on screen for a moment with its "Ajouté" label.
    var added by remember { mutableStateOf(addedPaths(context)) }
    var justAdded by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(justAdded) {
        if (justAdded.isNotEmpty()) {
            delay(700)
            added = added + justAdded
            justAdded = emptySet()
        }
    }
    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(emptySet<String>()) }

    val loaded = pending
    val visible = remember(loaded, added) {
        loaded?.let { p ->
            p.roots.map { root ->
                val counts = AddFolderTree.pendingCounts(root.path, p.photos[root.path].orEmpty(), added)
                AddFolderTree.visible(listOf(root), counts, added).singleOrNull()
            }.filterNotNull()
        }.orEmpty()
    }
    val hits = remember(visible, query) { AddFolderTree.hits(visible, storage, query) }
    val add: (String) -> Unit = { path ->
        Sources.addPath(context, path)
        justAdded = justAdded + path
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Ajouter un dossier", onBack = onBack)

        if (loaded == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Palette.Accent, strokeWidth = 2.dp, modifier = Modifier.size(28.dp))
            }
            return@Column
        }

        SearchField(
            query = query,
            onQuery = { query = it },
            suggestion = AddFolderTree.suggestion(hits, query),
        )

        Column(modifier = Modifier.fillMaxSize().padding(top = 12.dp).verticalScroll(rememberScrollState())) {
            if (query.isNotBlank()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Palette.Line),
                ) {
                    if (hits.isEmpty()) {
                        Text("Aucun dossier à ajouter", fontSize = 14.sp, color = Palette.Muted, modifier = Modifier.padding(14.dp))
                    }
                    hits.forEach { hit ->
                        HitRow(hit, justAdded = hit.path in justAdded, onAdd = { add(hit.path) })
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
                    }
                    Text(
                        "Revenir à l'arborescence",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Palette.Accent,
                        textAlign = TextAlign.End,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { query = "" }
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                }
            } else if (visible.isEmpty()) {
                Text(
                    "Tous les dossiers avec des photos sont déjà analysés.",
                    fontSize = 14.sp,
                    color = Palette.Muted,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                TreeRows(
                    nodes = visible,
                    depth = 0,
                    expanded = expanded,
                    justAdded = justAdded,
                    onAdd = add,
                    onToggle = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
                )
            }
        }
    }
}

/** The chosen folders, as paths. A folder chosen by the picker has no path and is left out. */
private fun addedPaths(context: Context): Set<String> =
    Sources.read(context).map { it.uri }.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet()

/** The folders of the storage with a photo to analyse under them, found by reading the photos. */
private fun findPending(context: Context, storage: String): Pending {
    val known = readKnown(LibraryStore.read(context))
    val roots = StorageTree.build(File(storage))
    val photos = roots.associate { root ->
        val scan = FolderScanner.scan(JavaDirNode(File(root.path)), reusable = { file -> reusableMime(known, file) })
        root.path to scan.candidates
            .filter { carry(known, it.file) == Carry.PROCESS }
            .map { it.file.id }
    }
    return Pending(roots, photos)
}

/** The search line: a loupe, the default text, the typed text, the grey suggestion, and a cross. */
@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit, suggestion: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().height(36.dp).padding(bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Canvas(Modifier.size(16.dp)) {
                val stroke = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
                drawCircle(Palette.Muted, radius = 6.5.dp.toPx(), center = Offset(7.dp.toPx(), 7.dp.toPx()), style = stroke)
                drawLine(Palette.Muted, Offset(12.dp.toPx(), 12.dp.toPx()), Offset(15.dp.toPx(), 15.dp.toPx()), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            }
            Box(modifier = Modifier.weight(1f)) {
                if (query.isEmpty()) {
                    Text("Rechercher un dossier", fontSize = 15.sp, color = Palette.Muted)
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQuery,
                    singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Palette.Ink),
                    visualTransformation = VisualTransformation { text ->
                        // The rest of the first folder name is shown after the typed text, in grey.
                        val shown = buildAnnotatedString {
                            append(text.text)
                            if (suggestion.isNotEmpty()) withStyle(SpanStyle(color = Palette.Muted)) { append(suggestion) }
                        }
                        TransformedText(shown, OffsetMapping.Identity)
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (query.isNotEmpty()) {
                Canvas(Modifier.size(12.dp).clickable { onQuery("") }) {
                    val stroke = 1.5.dp.toPx()
                    drawLine(Palette.Muted, Offset(0f, 0f), Offset(size.width, size.height), strokeWidth = stroke)
                    drawLine(Palette.Muted, Offset(size.width, 0f), Offset(0f, size.height), strokeWidth = stroke)
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFC4C9D1)))
    }
}

/** One folder found by the search: its name with the matched letters in dark blue, then its place. */
@Composable
private fun HitRow(hit: AddFolderTree.Hit, justAdded: Boolean, onAdd: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !justAdded, onClick = onAdd)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    append(hit.name.substring(0, hit.start))
                    withStyle(SpanStyle(color = Palette.Accent, fontWeight = FontWeight.SemiBold)) {
                        append(hit.name.substring(hit.start, hit.end))
                    }
                    append(hit.name.substring(hit.end))
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Ink,
            )
            Text(hit.label, fontSize = 12.sp, color = Palette.Muted)
        }
        if (justAdded) Text("Ajouté", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
    }
}

/** The folder tree, with a chevron to open a folder and a tap on its name to add it. */
@Composable
private fun TreeRows(
    nodes: List<FolderNode>,
    depth: Int,
    expanded: Set<String>,
    justAdded: Set<String>,
    onAdd: (String) -> Unit,
    onToggle: (String) -> Unit,
) {
    nodes.forEach { node ->
        val open = node.path in expanded
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.width((depth * 22).dp))
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clickable(enabled = node.children.isNotEmpty()) { onToggle(node.path) },
                contentAlignment = Alignment.Center,
            ) {
                if (node.children.isNotEmpty()) {
                    Text(if (open) "▾" else "▸", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                }
            }
            Row(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(enabled = node.path !in justAdded) { onAdd(node.path) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(node.name, fontSize = if (depth == 0) 15.sp else 14.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink, modifier = Modifier.weight(1f))
                if (node.path in justAdded) Text("Ajouté", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        if (open) {
            TreeRows(node.children, depth + 1, expanded, justAdded, onAdd, onToggle)
        }
    }
}
