package com.example.chatlab

/**
 * What the search screen shows for a query:
 * - [close]: the similar photos, above the chosen starting point;
 * - [extra]: the "less close" photos already revealed, one by one.
 * A photo appears only once.
 */
data class SearchOutcome(
    val close: List<Match>,
    val extra: List<Match>,
    /** Less close photos still available, up to [MoreResults.MAX_EXTRA]. */
    val remaining: Int,
    /** True if the limit of less close photos was reached while some were left. */
    val limitReached: Boolean,
) {
    val total: Int get() = close.size + extra.size
    val isEmpty: Boolean get() = total == 0
    val hasMore: Boolean get() = remaining > 0

    /** Number of photos the next tap on "Voir des photos moins proches" will add. */
    val nextBatch: Int get() = minOf(MoreResults.BATCH, remaining)

    companion object {
        val EMPTY = SearchOutcome(emptyList(), emptyList(), 0, false)
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
        shownExtra: Int,
        time: When,
        /** Called at each step, with its name: for the journal. Does nothing by default. */
        probe: (String) -> Unit = {},
    ): SearchOutcome {
        // Without words, a date alone still lists its photos: the ones of that date, in the order of the day.
        if (query.isBlank() && !filters.date.isActive) return SearchOutcome.EMPTY
        probe("kept.start")
        val kept = distinctById(entries, { it.source.ifBlank { it.path } }).filter { filters.matches(it, time) }
        probe("kept.end")
        if (query.isBlank()) {
            val day = kept.sortedByDescending { it.takenMs }
            return SearchOutcome(day.map { Match(it, null) }, emptyList(), 0, false)
        }
        val allowed = kept.map { it.source.ifBlank { it.path } }.toSet()
        probe("candidates.start")
        val candidates = distinctById(ranked, { keyOf(it) }).filter { match ->
            val key = keyOf(match)
            key in allowed
        }
        probe("candidates.end")
        probe("threshold.start")
        // The score decides which photos come (the threshold); the order shown is always the most recent first.
        // A file name typed again finds its photos first, whatever the description says.
        val named = nameMatches(kept, query)
        val namedKeys = named.map { keyOf(it) }.toSet()
        val byScore = Library.aboveThreshold(candidates.filter { keyOf(it) !in namedKeys }, filters.resemblance.floor)
            .sortedByDescending { it.entry.takenMs }
        // A complete file name ("IMG_20261127_093012.jpg") is looked for alone: when it matches, nothing else is shown.
        if (named.isNotEmpty() && isFileName(query)) {
            return SearchOutcome(named, emptyList(), 0, false)
        }
        val close = named + byScore
        val closeKeys = close.map { keyOf(it) }.toSet()
        val below = candidates.filter { keyOf(it) !in closeKeys && (it.score ?: 0f) >= MoreResults.FLOOR }
        val offered = below.take(MoreResults.MAX_EXTRA)
        probe("threshold.end")
        val shown = shownExtra.coerceIn(0, offered.size)
        return SearchOutcome(
            close = close,
            extra = offered.take(shown).sortedByDescending { it.entry.takenMs },
            remaining = offered.size - shown,
            limitReached = below.size > MoreResults.MAX_EXTRA && shown >= offered.size,
        )
    }

    /**
     * The photos whose name contains the query, read like the text (case and accents aside, the
     * punctuation of the name is ignored): "IMG_20261127_093012.jpg" finds that file. A query shorter
     * than three letters is not a name, so it does not pull in every photo. Most recent first.
     */
    fun nameMatches(entries: List<LibraryEntry>, query: String): List<Match> {
        val phrase = Library.fold(query)
        if (phrase.length < 3) return emptyList()
        return entries
            .filter { Library.fold(it.name).contains(phrase) }
            .sortedByDescending { it.takenMs }
            .map { Match(it, 1f) }
    }

    /** A name with an extension at its end, as copied from the name of a photo: "IMG_20261127_093012.jpg". */
    fun isFileName(query: String): Boolean = Regex("\\S+\\.[A-Za-z0-9]{2,5}").matches(query.trim())

    /** The number of less close photos after a new tap. */
    fun nextShown(outcome: SearchOutcome): Int = outcome.extra.size + outcome.nextBatch
}
