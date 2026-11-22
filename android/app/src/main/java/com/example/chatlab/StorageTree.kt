package com.example.chatlab

import java.io.File

/** Un dossier du stockage, avec ses sous-dossiers. Seuls les dossiers sont listés, jamais les fichiers. */
data class FolderNode(val path: String, val name: String, val children: List<FolderNode>)

/**
 * L'arbre des dossiers du stockage interne, tel qu'il est proposé pour le choix.
 * Exclus : les dossiers cachés (nom qui commence par un point), et les dossiers qu'Android
 * bloque même avec l'accès à tous les fichiers (Android/data et Android/obb).
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

    /** Vrai pour Android/data et Android/obb, à la racine du stockage. */
    private fun isBlocked(folder: File, root: File): Boolean =
        folder.parentFile?.absolutePath == File(root, "Android").absolutePath && folder.name in BLOCKED_UNDER_ANDROID
}
