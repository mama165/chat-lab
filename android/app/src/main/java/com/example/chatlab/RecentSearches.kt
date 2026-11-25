package com.example.chatlab

/** The last searches, from the most recent to the oldest, without duplicates. */
object RecentSearches {
    const val MAX = 8

    /** Adds a search at the top. Searches that are too short are not kept. */
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
