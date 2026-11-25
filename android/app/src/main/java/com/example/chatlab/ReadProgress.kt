package com.example.chatlab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Where the reading of each folder is (number of files examined), to show it while
 * the reading goes on. A folder absent from the list is not being read.
 *
 * A reading gets a number at the start. A reading started again (for example after "Tout
 * effacer") can no longer be stopped: the old one goes on to the end, but its figures
 * no longer reach the screen. Without this, two readings wrote into the same counter.
 */
object ReadProgress {
    /** An update every [EVERY] files: the screen stays smooth on a large folder. */
    const val EVERY = 50

    private val state = MutableStateFlow<Map<String, Int>>(emptyMap())
    val counts: StateFlow<Map<String, Int>> = state.asStateFlow()

    /** The total number of files of each folder being read: the total of the gauge. */
    private val totals = MutableStateFlow<Map<String, Int>>(emptyMap())
    val totalCounts: StateFlow<Map<String, Int>> = totals.asStateFlow()

    /** The reading in progress for each folder. */
    private val current = HashMap<String, Int>()
    private var lastNumber = 0

    /** Starts a reading of the folder, and returns its number. */
    @Synchronized
    fun start(folder: String, total: Int = 0): Int {
        val number = ++lastNumber
        current[folder] = number
        state.update { it + (folder to 0) }
        totals.update { it + (folder to total) }
        return number
    }

    @Synchronized
    fun report(folder: String, number: Int, examined: Int) {
        if (current[folder] != number || examined % EVERY != 0) return
        state.update { it + (folder to examined) }
    }

    @Synchronized
    fun finish(folder: String, number: Int) {
        if (current[folder] != number) return
        current.remove(folder)
        state.update { it - folder }
        totals.update { it - folder }
    }
}
