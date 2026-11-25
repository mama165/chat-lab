package com.example.chatlab

/**
 * Counts the files of a folder and its subfolders, without opening any. Serves as the total of
 * the gauge: we know how many files must be examined before starting.
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

/** The share done of a job, between 0 and 1: a gauge that follows the real progress. */
object Progress01 {
    fun of(done: Int, total: Int): Float {
        if (total <= 0) return 0f
        return (done.coerceIn(0, total).toFloat() / total)
    }
}
