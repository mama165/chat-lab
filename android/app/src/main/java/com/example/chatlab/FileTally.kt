package com.example.chatlab

/**
 * Compte les fichiers d'un dossier et de ses sous-dossiers, sans rien ouvrir. Sert de total à
 * la jauge : on sait combien de fichiers il faut examiner avant de commencer.
 */
object FileTally {
    fun count(root: DirNode): Int {
        val children = root.children() ?: return 0
        var total = 0
        for (child in children) {
            total += when (child) {
                is FileNode -> 1
                is DirNode -> count(child)
                else -> 0
            }
        }
        return total
    }
}

/** La part faite d'un travail, entre 0 et 1 : une jauge qui suit le vrai avancement. */
object Progress01 {
    fun of(done: Int, total: Int): Float {
        if (total <= 0) return 0f
        return (done.coerceIn(0, total).toFloat() / total)
    }
}
