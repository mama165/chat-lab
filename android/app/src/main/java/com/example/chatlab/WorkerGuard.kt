package com.example.chatlab

/**
 * How many workers the memory of the phone allows at the start of a run. Each worker loads its
 * own models, about [PER_WORKER_MB] of native memory; [RESERVE_MB] stays free for the system.
 */
object MemoryBudget {
    const val PER_WORKER_MB = 1300L
    const val RESERVE_MB = 600L

    /** At least one worker; never more than the level chosen; fewer when the memory is short. */
    fun workersAllowedAtStart(levelWorkers: Int, availMb: Long): Int {
        val byMemory = ((availMb - RESERVE_MB) / PER_WORKER_MB).toInt()
        return minOf(levelWorkers, byMemory).coerceAtLeast(1)
    }
}

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
    private val lowAvailMb: Long = 900L,
    private val cooldownMs: Long = 8_000L,
) {
    /** Number of workers allowed at the start of the run. */
    val startLimit: Int = initial.coerceAtLeast(1)

    /** Number of workers allowed to take a new photo. */
    var limit: Int = startLimit
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
