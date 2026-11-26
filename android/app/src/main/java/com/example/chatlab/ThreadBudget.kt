package com.example.chatlab

/**
 * How many threads each model session may use. The workers share the cores, and one core is kept free
 * for the system and the keyboard: without a limit, each session takes every core, and the workers fight
 * for them. Example with 8 cores: 7 are shared, so 1 thread per session with 4 workers, 3 with 2 workers.
 */
object ThreadBudget {
    fun perSession(cores: Int, workers: Int): Int {
        val shared = (cores - 1).coerceAtLeast(1)
        return (shared / workers.coerceAtLeast(1)).coerceAtLeast(1)
    }
}
