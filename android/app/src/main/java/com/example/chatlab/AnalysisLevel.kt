package com.example.chatlab

/**
 * How hard an analysis works: the number of photos analysed at the same time. Each worker
 * holds its own models, so a higher level uses more memory and more processor.
 */
enum class AnalysisLevel(val key: String, val label: String, val workers: Int) {
    ECONOMIQUE("economique", "Économique", 1),
    EQUILIBRE("equilibre", "Équilibré", 2),
    PUISSANT("puissant", "Puissant", 3),
    MAXIMAL("maximal", "Maximal", 4);

    companion object {
        /** The default level, used when nothing was chosen or the saved key is unknown. */
        val DEFAULT = EQUILIBRE

        fun fromKey(key: String?): AnalysisLevel = entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}
