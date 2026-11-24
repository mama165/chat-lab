package com.example.chatlab

import android.content.Context

/**
 * A test-only override of the number of workers at the start of an analysis. Off by default:
 * the number then comes from the performance level and the memory at the start, as usual. When
 * set, the start memory budget is bypassed and the given number is used; the memory guard still
 * lowers it if the memory falls too low during the run.
 */
object WorkerOverride {
    private const val KEY = "forced_workers"
    private const val AUTO = "auto"

    /** The forced number from the saved value, or null when the automatic choice is kept. */
    fun parse(raw: String?): Int? = raw?.toIntOrNull()?.takeIf { it in 1..4 }

    fun read(context: Context): Int? =
        parse(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    /** Stores the forced number, or the automatic choice when [workers] is null. */
    fun write(context: Context, workers: Int?) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, workers?.toString() ?: AUTO)
            .apply()
    }
}
