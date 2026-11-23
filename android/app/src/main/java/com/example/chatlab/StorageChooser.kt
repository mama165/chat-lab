package com.example.chatlab

import android.os.Environment
import android.os.StatFs
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.example.chatlab.ui.OutlineButton
import com.example.chatlab.ui.PageHeader
import com.example.chatlab.ui.Palette
import com.example.chatlab.ui.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** The tree, and the number of photos under each folder. [done]: the counting is finished. */
private data class Counting(val tree: List<FolderNode>, val counts: Map<String, Int>, val done: Boolean)

/**
 * The choice of the folders to analyse in the internal storage, as in mockups 1.5 to 1.10:
 * the folder tree with checkboxes, the number of photos on the right of each folder, "Tout
 * cocher" and "Tout décocher". A ticked folder includes its subfolders; the folders already
 * added stay locked.
 */
@Composable
fun StorageChooser(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.granted()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // When coming back from the Android settings, we check the permission again.
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

    val counting by produceState<Counting?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { count(Environment.getExternalStorageDirectory().path) }
    }
    val added = remember { Sources.read(context).map { it.uri } }
    val locked = remember(added) { added.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val storage = remember { storageSummary() }
    val tops = FolderSelection.tops(selected)
    val count = tops.size
    val addSelection: () -> Unit = {
        tops.forEach { Sources.addPath(context, it) }
        onDone()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Dossiers", onBack = onBack, action = "Fait", actionEnabled = count > 0, onAction = addSelection)

        val state = counting
        Row(
            modifier = Modifier.padding(top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (state == null || !state.done) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), color = Palette.Accent, strokeWidth = 2.dp)
                Text("Lecture de « Stockage interne » en cours", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Palette.Accent)
            } else {
                val folders = FolderSelection.all(state.tree).size
                val photos = state.tree.sumOf { state.counts[it.path] ?: 0 }
                Text(
                    "Comptage terminé · ${Formats.photos(photos)} dans $folders dossiers",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Accent,
                )
            }
        }

        // The storage line: name, then space used.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Palette.Field)
                .padding(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("Stockage interne", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                Text(storage, fontSize = 12.sp, color = Palette.Muted)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Text(
                "Tout cocher",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Accent,
                modifier = Modifier.clickable {
                    if (state != null) selected = FolderSelection.selectAll(state.tree, locked)
                }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "Tout décocher",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = Palette.Accent,
                modifier = Modifier.clickable { selected = FolderSelection.clear() }.padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }

        if (state != null) {
            val scroll = rememberScrollState()
            BoxWithConstraints(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 14.dp)) {
                    FolderRows(
                        nodes = state.tree,
                        depth = 0,
                        locked = locked,
                        selected = selected,
                        expanded = expanded,
                        counts = state.counts,
                        onToggleSelect = { node -> selected = FolderSelection.toggle(node, selected, locked) },
                        onToggleExpand = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
                    )
                }
                ScrollThumb(scroll, Modifier.align(Alignment.TopEnd))
            }
        } else {
            Spacer(Modifier.weight(1f))
        }

        Text(
            "Les dossiers cachés (dont le nom commence par un point) ne sont pas proposés.",
            fontSize = 12.sp,
            color = Palette.Muted,
            modifier = Modifier.padding(top = 8.dp),
        )
        Column(modifier = Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (count == 0) {
                Box(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Palette.Line).padding(vertical = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("Cochez au moins un dossier", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Palette.Muted)
                }
            } else {
                PrimaryButton(if (count == 1) "Ajouter 1 dossier" else "Ajouter $count dossiers", onClick = addSelection)
            }
        }
    }
}

/**
 * The photos of each top-level folder, counted in one pass: each top-level folder
 * is walked once, and each photo counts for all its parent folders.
 */
private fun count(root: String): Counting {
    val tree = StorageTree.build(File(root))
    val counts = HashMap<String, Int>()
    for (node in tree) {
        val photos = FolderScanner.scan(JavaDirNode(File(node.path))).candidates.map { it.file.id }
        counts.putAll(FolderCounts.countByFolder(photos, node.path).mapValues { it.value })
        counts[node.path] = photos.size
    }
    return Counting(tree, counts, done = true)
}

/** The folders of one level, then their subfolders if the parent is expanded. */
@Composable
private fun FolderRows(
    nodes: List<FolderNode>,
    depth: Int,
    locked: Set<String>,
    selected: Set<String>,
    expanded: Set<String>,
    counts: Map<String, Int>,
    onToggleSelect: (FolderNode) -> Unit,
    onToggleExpand: (String) -> Unit,
) {
    nodes.forEach { node ->
        val isLocked = node.path in locked
        val on = node.path in selected
        val partial = FolderSelection.isPartial(node, selected)
        val open = node.path in expanded
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 22).dp, top = 10.dp, bottom = 10.dp)
                .clickable(enabled = node.children.isNotEmpty()) { onToggleExpand(node.path) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                if (node.children.isEmpty()) "" else if (open) "▾" else "▸",
                fontSize = 14.sp,
                color = if (open) Palette.Accent else Palette.Muted,
                modifier = Modifier.width(22.dp),
            )
            // A checkbox, with the same drawing as the mockup: filled, ticked, partial or empty.
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .then(
                        when {
                            on -> Modifier.background(Palette.Accent)
                            isLocked -> Modifier.background(Palette.Soft)
                            partial -> Modifier.background(Palette.Soft).border(2.dp, Palette.Accent, RoundedCornerShape(6.dp))
                            else -> Modifier.border(2.dp, Palette.Line, RoundedCornerShape(6.dp))
                        },
                    )
                    .clickable(enabled = !isLocked) { onToggleSelect(node) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    on || isLocked -> Text("✓", fontSize = 13.sp, color = if (on) Color.White else Palette.Accent)
                    partial -> Box(Modifier.size(width = 10.dp, height = 2.dp).background(Palette.Accent))
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    node.name,
                    fontSize = if (depth == 0) 15.sp else 14.sp,
                    fontWeight = if (depth == 0) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isLocked) Palette.Muted else Palette.Ink,
                )
                if (isLocked) Text("Déjà ajouté", fontSize = 12.sp, color = Palette.Muted)
            }
            val photos = counts[node.path]
            Text(
                if (photos == null) "" else Formats.photos(photos),
                fontSize = 13.sp,
                color = Palette.Muted,
            )
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        if (open) {
            FolderRows(node.children, depth + 1, locked, selected, expanded, counts, onToggleSelect, onToggleExpand)
        }
    }
}

/** The thin scrollbar on the right of the list: its position follows the scrolling. */
@Composable
private fun ScrollThumb(scroll: ScrollState, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier = modifier.fillMaxHeight().width(4.dp)) {
        val track = maxHeight
        val thumb = track * 0.4f
        val max = scroll.maxValue.coerceAtLeast(1)
        val progress = scroll.value.toFloat() / max
        if (scroll.maxValue > 0) {
            Box(
                modifier = Modifier
                    .offset(y = (track - thumb) * progress)
                    .size(width = 4.dp, height = thumb)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Palette.Line),
            )
        }
    }
}

/** "84 Go utilisés sur 119 Go", read from the internal storage. */
private fun storageSummary(): String {
    return try {
        val stat = StatFs(Environment.getExternalStorageDirectory().path)
        val total = stat.totalBytes
        val used = total - stat.availableBytes
        "${Formats.size(used)} utilisés sur ${Formats.size(total)}"
    } catch (e: Exception) {
        "Espace inconnu"
    }
}

@Composable
private fun AccessPanel(onOpenSettings: () -> Unit, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Palette.Page).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Dossiers", onBack = onBack)
        Spacer(Modifier.height(24.dp))
        Text("Accès à vos fichiers", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = Palette.Ink)
        Text(
            "Pour choisir les dossiers à analyser, ChatLab doit voir les dossiers du téléphone. Les photos restent sur le téléphone et ne sont jamais modifiées.",
            fontSize = 14.sp,
            color = Palette.Muted,
        )
        Text(
            "Android ouvrira ses réglages. Activez « Autoriser l'accès à tous les fichiers », puis revenez ici.",
            fontSize = 13.sp,
            color = Palette.Muted,
        )
        Spacer(Modifier.weight(1f))
        PrimaryButton("Ouvrir les réglages", onClick = onOpenSettings)
        OutlineButton("Plus tard", onClick = onBack, modifier = Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

/** All the folders of the tree, flat. */
private fun flatten(node: FolderNode): List<FolderNode> = listOf(node) + node.children.flatMap(::flatten)

private fun flatten(nodes: List<FolderNode>): List<FolderNode> = nodes.flatMap(::flatten)

/** True if a subfolder of [node] is ticked. */
private fun selectedUnder(node: FolderNode, selected: Set<String>): Boolean =
    node.children.any { it.path in selected || selectedUnder(it, selected) }

/** The parent folder of a path, if it is in the tree. */
private fun parentOf(path: String, all: List<FolderNode>): String? =
    File(path).parent?.takeIf { parent -> all.any { it.path == parent } }
