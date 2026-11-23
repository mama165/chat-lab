package com.example.chatlab

import java.io.File

/**
 * The logic of the checkboxes of the folder tree, without display: what is ticked, what is
 * partly ticked, and what is actually added. A ticked folder includes its subfolders.
 * The folders already added ([locked]) never change.
 */
object FolderSelection {
    /** The folder and all its subfolders, flat. */
    fun withDescendants(node: FolderNode): List<FolderNode> = listOf(node) + node.children.flatMap(::withDescendants)

    /** All the folders of the tree, flat. */
    fun all(roots: List<FolderNode>): List<FolderNode> = roots.flatMap(::withDescendants)

    /** True if at least one subfolder of [node] is ticked, without [node] being ticked. */
    fun isPartial(node: FolderNode, selected: Set<String>): Boolean =
        node.path !in selected && selectedUnder(node, selected)

    private fun selectedUnder(node: FolderNode, selected: Set<String>): Boolean =
        node.children.any { it.path in selected || selectedUnder(it, selected) }

    /**
     * Ticks or unticks [node] and its subfolders. If the folder is ticked or partly ticked,
     * it is unticked; otherwise it is ticked. A locked folder is never ticked.
     */
    fun toggle(node: FolderNode, selected: Set<String>, locked: Set<String>): Set<String> {
        val family = withDescendants(node).map { it.path }.toSet()
        return if (node.path in selected || selectedUnder(node, selected)) {
            selected - family
        } else {
            selected + family.filter { it !in locked }
        }
    }

    /** "Tout cocher": all the folders, except those already added. */
    fun selectAll(roots: List<FolderNode>, locked: Set<String>): Set<String> =
        all(roots).map { it.path }.filter { it !in locked }.toSet()

    /** "Tout décocher": nothing, except the folders already added, which stay as they are. */
    fun clear(): Set<String> = emptySet()

    /**
     * The folders to add: the highest among the ticked ones, since their subfolders follow
     * them. A folder whose parent is ticked is not repeated.
     */
    fun tops(selected: Set<String>): List<String> =
        selected.filter { path -> parentOf(path)?.let { it !in selected } ?: true }.sorted()

    /** The path of the parent folder. */
    fun parentOf(path: String): String? = File(path).parent
}
