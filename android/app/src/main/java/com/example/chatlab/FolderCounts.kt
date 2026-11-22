package com.example.chatlab

import java.io.File

/**
 * Le nombre de photos sous chaque dossier, calculé en une seule passe sur les photos trouvées.
 * Une photo compte pour chacun de ses dossiers parents, jusqu'à la racine du stockage. Un dossier
 * sans photo n'apparaît pas dans le résultat : son nombre est zéro.
 */
object FolderCounts {
    fun countByFolder(photoPaths: Collection<String>, root: String): Map<String, Int> {
        val counts = HashMap<String, Int>()
        val top = File(root).absolutePath
        for (path in photoPaths) {
            var parent = File(path).parentFile
            while (parent != null && parent.absolutePath.startsWith(top) && parent.absolutePath != File(top).parent) {
                counts[parent.absolutePath] = (counts[parent.absolutePath] ?: 0) + 1
                if (parent.absolutePath == top) break
                parent = parent.parentFile
            }
        }
        return counts
    }
}
