package com.example.chatlab

import java.io.File

/** A folder of the storage, with its subfolders. Only folders are listed, never files. */
data class FolderNode(val path: String, val name: String, val children: List<FolderNode>)

/**
 * The folder tree of the internal storage, as it is offered for the choice.
 * Excluded: hidden folders (name starting with a dot), and the folders that Android
 * blocks even with the all-files access (Android/data and Android/obb).
 */
object StorageTree {
    private val BLOCKED_UNDER_ANDROID = setOf("data", "obb")

    fun build(root: File, maxDepth: Int = 12): List<FolderNode> = children(root, root, maxDepth)

    private fun children(dir: File, root: File, depthLeft: Int): List<FolderNode> {
        if (depthLeft <= 0) return emptyList()
        val entries = dir.listFiles { file -> file.isDirectory } ?: return emptyList()
        return entries
            .filter { !it.name.startsWith(".") }
            .filterNot { isBlocked(it, root) }
            .sortedBy { it.name.lowercase() }
            .map { FolderNode(it.absolutePath, it.name, children(it, root, depthLeft - 1)) }
    }

    /** True for Android/data and Android/obb, at the root of the storage. */
    private fun isBlocked(folder: File, root: File): Boolean =
        folder.parentFile?.absolutePath == File(root, "Android").absolutePath && folder.name in BLOCKED_UNDER_ANDROID
}
