package com.example.chatlab

/** How many photos fit on one row of the results: from 1 (one large photo) to 4 (small ones). */
internal object PerRow {
    const val MIN = 1
    const val MAX = 4
    const val DEFAULT = 3

    /** A value out of the range is brought back into it. */
    fun clamp(value: Int): Int = value.coerceIn(MIN, MAX)
}

/**
 * The "Photos par ligne" control is shown only once there are results to rearrange: a sentence is typed,
 * some photos match it, and the search is not still running.
 */
internal fun perRowControlShown(query: String, hasResults: Boolean, loading: Boolean, ranking: Boolean): Boolean =
    query.isNotBlank() && hasResults && !loading && !ranking
