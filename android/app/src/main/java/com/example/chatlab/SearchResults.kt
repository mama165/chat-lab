package com.example.chatlab

/**
 * What the search screen shows for a query:
 * - [words]: the photos whose text read (or name) contains all the words;
 * - [close]: the similar photos, above the chosen starting point;
 * - [extra]: the "less close" photos already revealed, one by one.
 * A photo appears only once, and never if it was removed from this search.
 */
data class SearchOutcome(
    val words: List<Match>,
    val close: List<Match>,
    val extra: List<Match>,
    /** Less close photos still available, up to [MoreResults.MAX_EXTRA]. */
    val remaining: Int,
    /** True if the limit of less close photos was reached while some were left. */
    val limitReached: Boolean,
) {
    val total: Int get() = words.size + close.size + extra.size
    val isEmpty: Boolean get() = total == 0
    val hasMore: Boolean get() = remaining > 0

    /** Number of photos the next tap on "Voir des photos moins proches" will add. */
    val nextBatch: Int get() = minOf(MoreResults.BATCH, remaining)

    companion object {
        val EMPTY = SearchOutcome(emptyList(), emptyList(), emptyList(), 0, false)
    }
}

/** Settings of the one-by-one drip of "Voir des photos moins proches". */
object MoreResults {
    /** Photos added at each tap. */
    const val BATCH = 6

    /** At most this number of less close photos for a search. */
    const val MAX_EXTRA = 30

    /** Below this score, a photo does not look enough alike to be offered. */
    const val FLOOR = 0.03f
}

object SearchResults {
    /** Key of a photo: its original address, or its path otherwise. */
    fun keyOf(match: Match): String = match.entry.source.ifBlank { match.entry.path }

    /**
     * Computes the results. [ranked] is the full ranking by description (empty if the
     * description is not read yet); [shownExtra] the number of less close photos already
     * revealed.
     */
    fun compute(
        entries: List<LibraryEntry>,
        query: String,
        ranked: List<Match>,
        filters: Filters,
        removed: Set<String>,
        shownExtra: Int,
        time: When,
    ): SearchOutcome {
        if (query.isBlank()) return SearchOutcome.EMPTY
        val kept = distinctById(entries, { it.source.ifBlank { it.path } }).filter { filters.matches(it, time) }
        val words = Library.searchWords(kept, query, Place.ALL).filter { keyOf(it) !in removed }
        val wordKeys = words.map { keyOf(it) }.toSet()
        val allowed = kept.map { it.source.ifBlank { it.path } }.toSet()
        val candidates = distinctById(ranked, { keyOf(it) }).filter { match ->
            val key = keyOf(match)
            key in allowed && key !in removed && key !in wordKeys
        }
        val close = Library.aboveThreshold(candidates, filters.resemblance.floor)
        val closeKeys = close.map { keyOf(it) }.toSet()
        val below = candidates.filter { keyOf(it) !in closeKeys && (it.score ?: 0f) >= MoreResults.FLOOR }
        val offered = below.take(MoreResults.MAX_EXTRA)
        val shown = shownExtra.coerceIn(0, offered.size)
        return SearchOutcome(
            words = words,
            close = close,
            extra = offered.take(shown),
            remaining = offered.size - shown,
            limitReached = below.size > MoreResults.MAX_EXTRA && shown >= offered.size,
        )
    }

    /** The number of less close photos after a new tap. */
    fun nextShown(outcome: SearchOutcome): Int = outcome.extra.size + outcome.nextBatch
}
