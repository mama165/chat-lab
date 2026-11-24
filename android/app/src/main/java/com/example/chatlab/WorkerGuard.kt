package com.example.chatlab

/**
 * Keeps the analysis under the memory limit of the phone. It only ever lowers the number of
 * workers, one at a time: each worker loads its own models (about 1.5 GB of native memory on
 * the test phone), and Android stops the app when the available memory stays too low.
 *
 * A reduction happens when the available memory falls below [lowAvailMb], or when Android asks
 * for memory. Two reductions are kept at least [cooldownMs] apart, so that the memory has time
 * to be released before the next decision.
 */
class WorkerGuard(
    initial: Int,
    private val lowAvailMb: Long = 600L,
    private val cooldownMs: Long = 20_000L,
) {
    /** Number of workers allowed to take a new photo. */
    var limit: Int = initial.coerceAtLeast(1)
        private set

    /** How many times the limit was lowered during the run. */
    var reductions: Int = 0
        private set

    private var lastChange = Long.MIN_VALUE

    /**
     * Looks at the memory at [now]. Returns the new limit when it was lowered, null otherwise.
     * [pressure] is true when Android asked for memory (trim request) since the last look.
     */
    fun observe(now: Long, availMb: Long, lowMemory: Boolean, pressure: Boolean = false): Int? {
        if (limit <= 1) return null
        if (!(lowMemory || pressure || availMb < lowAvailMb)) return null
        if (lastChange != Long.MIN_VALUE && now - lastChange < cooldownMs) return null
        limit -= 1
        reductions += 1
        lastChange = now
        return limit
    }
}
