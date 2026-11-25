package com.example.chatlab

/** How long a search may wait for its ranking before the screen says it took too long. */
internal const val SEARCH_LIMIT_MS = 10_000L

/** What the results area shows while the sentence is being ranked. */
internal enum class Waiting { NONE, RANKING, TIMED_OUT }

/**
 * True while the photos are not yet ranked for the sentence typed: the sentence has no vector yet,
 * or the ranking still belongs to an earlier vector. A blank query or a failed model is never pending.
 */
internal fun rankingPending(
    queryBlank: Boolean,
    modelFailed: Boolean,
    vector: FloatArray?,
    rankedFor: FloatArray?,
): Boolean = !queryBlank && !modelFailed && (vector == null || rankedFor !== vector)

/** The waiting state shown in place of "Aucune photo": the gauge, or the message after the limit. */
internal fun waitingFor(pending: Boolean, timedOut: Boolean): Waiting = when {
    !pending -> Waiting.NONE
    timedOut -> Waiting.TIMED_OUT
    else -> Waiting.RANKING
}
