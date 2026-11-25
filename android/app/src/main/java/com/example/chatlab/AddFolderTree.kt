package com.example.chatlab

import java.text.Normalizer

/**
 * What the "+" page offers: only the folders that still have a photo to analyse. A folder
 * is offered when a photo under it is new or changed, at any depth; a folder already added
 * (and all its subfolders) is never offered again. Folders with nothing left disappear.
 */
object AddFolderTree {
    /** The folders to offer, with their subfolders: a folder without a photo to analyse under it is removed. */
    fun visible(nodes: List<FolderNode>, pending: Map<String, Int>, added: Set<String>): List<FolderNode> =
        nodes
            .filter { !covered(it.path, added) && (pending[it.path] ?: 0) > 0 }
            .map { it.copy(children = visible(it.children, pending, added)) }

    /** True when the folder is an added folder or lies under one: the analysis of that folder already covers it. */
    fun covered(path: String, added: Set<String>): Boolean =
        added.any { path == it || path.startsWith("$it/") }

    /**
     * Number of photos to analyse under each folder of [root], from the photos found that are
     * not known yet. Photos under an added folder are left out.
     */
    fun pendingCounts(root: String, pendingPhotos: Collection<String>, added: Set<String>): Map<String, Int> =
        FolderCounts.countByFolder(pendingPhotos.filter { !covered(it, added) }, root)

    /** A folder that matches the search. [start] and [end] are the matched letters inside [name]. */
    data class Hit(val path: String, val name: String, val label: String, val start: Int, val end: Int)

    /**
     * The folders whose name contains the query, the ones starting with it first. The match
     * ignores case and accents, and keeps the letters' positions so that they can be highlighted.
     */
    fun hits(nodes: List<FolderNode>, root: String, query: String): List<Hit> {
        val needle = key(query.trim())
        if (needle.isEmpty()) return emptyList()
        return FolderSelection.all(nodes)
            .mapNotNull { node ->
                val at = key(node.name).indexOf(needle)
                if (at < 0) null else Hit(node.path, node.name, labelOf(node.path, root), at, at + needle.length)
            }
            .sortedWith(compareBy<Hit>({ it.start != 0 }, { it.label.lowercase() }))
    }

    /** The rest of the first folder name that starts with the query: shown in grey as a suggestion. */
    fun suggestion(hits: List<Hit>, query: String): String {
        val first = hits.firstOrNull { it.start == 0 } ?: return ""
        return if (first.name.length > query.trim().length) first.name.substring(query.trim().length) else ""
    }

    /** "Pictures › Screenshots": the folder's path from the root of the storage. */
    fun labelOf(path: String, root: String): String =
        path.removePrefix("$root/").split("/").joinToString(" › ")

    /** A letter-by-letter key of the text, lower case and without accents. The length does not change. */
    internal fun key(text: String): String =
        text.map { c ->
            Normalizer.normalize(c.lowercaseChar().toString(), Normalizer.Form.NFD).first()
        }.joinToString("")
}
