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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import androidx.compose.runtime.collectAsState
import java.io.File

/** The fraction counted of each top-level folder, from 0 to 1 (1 when the count is finished). */
private object CountProgress {
    val fractions = MutableStateFlow<Map<String, Float>>(emptyMap())

    fun set(path: String, fraction: Float) = fractions.update { it + (path to fraction) }

    fun reset() = fractions.update { emptyMap() }
}

/**
 * Le choix des dossiers à analyser au premier lancement, comme sur la maquette : pendant le comptage,
 * « Veuillez patienter » avec le pourcentage ; ensuite, les dossiers de premier niveau, repliés, avec le nombre
 * total de photos de chacun. Une flèche ouvre les sous-dossiers. Cocher un dossier l'ajoute à l'analyse, avec ses
 * sous-dossiers ; les dossiers déjà ajoutés restent verrouillés.
 */
@Composable
fun StorageChooser(onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(StorageAccess.granted()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Au retour des réglages Android, on vérifie de nouveau l'autorisation.
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

    // L'arbre d'abord ; puis le nombre total de photos de chaque dossier, sous-dossiers compris.
    val tree by produceState<List<FolderNode>?>(initialValue = null) {
        value = withContext(Dispatchers.IO) { StorageTree.build(Environment.getExternalStorageDirectory()) }
    }
    val counts by produceState<Map<String, Int>?>(initialValue = null, tree) {
        val roots = tree ?: return@produceState
        CountProgress.reset()
        value = withContext(Dispatchers.IO) { countAll(roots) }
    }
    val fractions by CountProgress.fractions.collectAsState()
    val added = remember { Sources.read(context).map { it.uri } }
    val locked = remember(added) { added.filter { it.startsWith("file:") }.map { it.removePrefix("file:") }.toSet() }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    val roots = tree
    val shown = counts
    val tops = FolderSelection.tops(selected)
    val count = tops.size
    val addSelection: () -> Unit = {
        tops.forEach { Sources.addPath(context, it) }
        onDone()
    }
    // La part comptée, en moyenne sur les dossiers de premier niveau : elle affiche le pourcentage.
    val percent = if (roots.isNullOrEmpty()) 0 else {
        (roots.sumOf { (fractions[it.path] ?: 0f).toDouble() } / roots.size * 100).toInt()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Palette.Page)
            .padding(horizontal = 20.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        PageHeader("Dossiers", onBack = onBack)

        if (roots != null && shown != null) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                Text(
                    "Tout cocher",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Palette.Accent,
                    modifier = Modifier.clickable {
                        selected = FolderSelection.selectAll(roots, locked, shown)
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
            val scroll = rememberScrollState()
            BoxWithConstraints(modifier = Modifier.weight(1f).padding(top = 6.dp)) {
                Column(modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(end = 14.dp)) {
                    FolderRows(
                        nodes = roots,
                        depth = 0,
                        locked = locked,
                        selected = selected,
                        expanded = expanded,
                        counts = shown,
                        onToggleSelect = { node -> selected = FolderSelection.toggle(node, selected, locked, shown) },
                        onToggleExpand = { path -> expanded = if (path in expanded) expanded - path else expanded + path },
                    )
                }
                ScrollThumb(scroll, Modifier.align(Alignment.TopEnd))
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
                    PrimaryButton("Ajouter les dossiers cochés", onClick = addSelection)
                }
            }
        } else {
            // Pendant le comptage : rien d'autre que le message et le pourcentage.
            Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Veuillez patienter", fontSize = 15.sp, color = Palette.Muted)
                    Text("$percent %", fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = Palette.Ink)
                }
            }
        }
    }
}

/**
 * Compte, pour chaque dossier, les photos qu'il contient, sous-dossiers compris. Les photos sont lues dossier par
 * dossier ; la jauge suit les fichiers lus sur le total de chaque dossier de premier niveau.
 */
private fun countAll(roots: List<FolderNode>): Map<String, Int> {
    val counts = HashMap<String, Int>()
    for (node in roots) {
        val dir = JavaDirNode(File(node.path))
        val total = FileTally.count(dir)
        CountProgress.set(node.path, Progress01.of(0, total))
        val photos = FolderScanner.scan(dir, examined = { n ->
            CountProgress.set(node.path, Progress01.of(n, total))
        }).candidates.map { it.file.id }
        counts.putAll(FolderCounts.countByFolder(photos, node.path))
        counts[node.path] = photos.size
        // Every empty subfolder is set to zero, so that the chooser can hide it.
        counts.putAll(FolderCounts.zeroFill(node, counts))
        CountProgress.set(node.path, 1f)
    }
    return counts
}

/** Les dossiers d'un niveau, puis leurs sous-dossiers si le parent est ouvert. Une flèche ouvre, la ligne coche. */
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
    nodes.filter { ChooserFilter.visible(it.path, counts, locked) }.forEach { node ->
        val isLocked = node.path in locked
        val on = node.path in selected
        val partial = FolderSelection.isPartial(node, selected)
        val open = node.path in expanded
        val children = node.children.filter { ChooserFilter.visible(it.path, counts, locked) }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = (depth * 22).dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // La flèche n'existe que pour un dossier qui a des sous-dossiers à afficher.
            Box(
                modifier = Modifier
                    .width(36.dp)
                    .height(36.dp)
                    .clickable(enabled = children.isNotEmpty()) { onToggleExpand(node.path) },
                contentAlignment = Alignment.Center,
            ) {
                if (children.isNotEmpty()) {
                    Text(if (open) "▾" else "▸", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Palette.Accent)
                }
            }
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
            Text(Formats.photos(counts[node.path] ?: 0), fontSize = 13.sp, color = Palette.Muted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(Palette.Line))
        if (open) {
            FolderRows(children, depth + 1, locked, selected, expanded, counts, onToggleSelect, onToggleExpand)
        }
    }
}

/** La barre de défilement fine à droite de la liste : sa position suit le défilement. */
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

@Composable
internal fun AccessPanel(onOpenSettings: () -> Unit, onBack: () -> Unit) {
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

/** Tous les dossiers de l'arbre, à plat. */
private fun flatten(node: FolderNode): List<FolderNode> = listOf(node) + node.children.flatMap(::flatten)
