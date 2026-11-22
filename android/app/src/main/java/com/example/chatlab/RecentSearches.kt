package com.example.chatlab

/** Les dernières recherches, de la plus récente à la plus ancienne, sans doublon. */
object RecentSearches {
    const val MAX = 8

    /** Ajoute une recherche en tête. Les recherches trop courtes ne sont pas gardées. */
    fun add(list: List<String>, query: String, max: Int = MAX): List<String> {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.length < 2) return list
        val key = Library.fold(clean)
        return (listOf(clean) + list.filter { Library.fold(it) != key }).take(max)
    }

    fun encode(list: List<String>): String = list.joinToString("\n")

    fun decode(text: String?): List<String> =
        text.orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
}
