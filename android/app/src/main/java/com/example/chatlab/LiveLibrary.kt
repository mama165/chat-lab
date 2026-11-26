package com.example.chatlab

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The library the search reads, kept in memory. A full read of the store happens only when the data changes
 * as a whole (start, wipe, change of folders, end of a run). During an analysis, each saved batch is added
 * here: the search finds the new photos at once, without reading the whole library again.
 */
object LiveLibrary {
    private val entries = LinkedHashMap<String, LibraryEntry>()
    private val version = MutableStateFlow(0)

    /** Goes up at each change: the screens read the library again when it moves. */
    val changes: StateFlow<Int> = version.asStateFlow()

    @Synchronized
    fun replaceAll(list: List<LibraryEntry>) {
        entries.clear()
        list.forEach { entries[it.id] = it }
        version.value = version.value + 1
    }

    /** Adds the photos, or replaces them when they are analysed again. */
    @Synchronized
    fun publish(list: List<LibraryEntry>) {
        if (list.isEmpty()) return
        list.forEach { entries[it.id] = it }
        version.value = version.value + 1
    }

    @Synchronized
    fun snapshot(): List<LibraryEntry> = ArrayList(entries.values)
}
