package com.example.chatlab

/**
 * How hard an analysis works: the number of photos analysed at the same time. Each worker
 * holds its own models, so a higher level uses more memory and more processor.
 */
enum class AnalysisLevel(val key: String, val label: String, val workers: Int) {
    ECONOMIQUE("economique", "Léger", 1),
    EQUILIBRE("equilibre", "Normal", 2),
    PUISSANT("puissant", "Rapide", 3),
    MAXIMAL("maximal", "Maximum", 4);

    /** "1 photo à la fois", "2 photos à la fois": under the choice of the performance. */
    fun photosAtOnce(): String = if (workers == 1) "1 photo à la fois" else "$workers photos à la fois"

    companion object {
        /** The default level, used when nothing was chosen or the saved key is unknown. */
        val DEFAULT = EQUILIBRE

        fun fromKey(key: String?): AnalysisLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
