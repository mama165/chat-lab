package com.example.chatlab

/**
 * The part of the ranking that can be done in steps: while the sentence stays the same, a change of the
 * library scores only the photos that were not scored yet, and their results join the ones already ranked.
 */
object IncrementalRanking {
    /** The photos to score: all of them for a new sentence, only the new ones otherwise. */
    fun photosToScore(entries: List<LibraryEntry>, scoredIds: Set<String>, sameQuery: Boolean): List<LibraryEntry> =
        if (sameQuery) entries.filter { it.id !in scoredIds } else entries

    /** The ranking with the new results, best first. */
    fun merge(ranked: List<Match>, found: List<Match>): List<Match> =
        (ranked + found).sortedByDescending { it.score ?: 0f }
}
