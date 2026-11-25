package com.example.chatlab

/**
 * A value computed once per version. [get] runs [load] only when the version differs from the one kept, and
 * returns the value kept otherwise. The library uses it: it is read again when the data changes, not on each
 * change of tab. A failed load is not kept, so the next call tries again.
 */
class VersionedCache<T : Any>(private val load: () -> T) {
    private var keptVersion: Int? = null
    private var kept: T? = null

    @Synchronized
    fun get(version: Int): T {
        val current = kept
        if (current != null && keptVersion == version) return current
        val fresh = load()
        kept = fresh
        keptVersion = version
        return fresh
    }
}
