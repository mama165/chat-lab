package com.example.chatlab

/** One loading of the models, for the record of an analysis. */
data class ModelLoad(val reload: Boolean, val atMs: Long, val millis: Long)

/**
 * Holds the models of one worker. They are loaded on first use, released when the analysis
 * is paused, and loaded again on resume. [load] and [clock] are injected to test the rules
 * without the phone.
 */
class AnalyzerSlot<T : AutoCloseable>(
    private val load: () -> T,
    private val clock: () -> Long = System::currentTimeMillis,
    private val onLoad: (ModelLoad) -> Unit = {},
) {
    private var analyzer: T? = null
    private var loadedBefore = false

    /** True while the models are in memory. */
    val loaded: Boolean get() = analyzer != null

    /** The models, loaded now if they were released. */
    fun get(): T {
        analyzer?.let { return it }
        val reload = loadedBefore
        val started = clock()
        val fresh = load()
        onLoad(ModelLoad(reload = reload, atMs = started, millis = clock() - started))
        analyzer = fresh
        loadedBefore = true
        return fresh
    }

    /** Releases the models: their sessions are closed and the memory goes back to the system. */
    fun release() {
        analyzer?.close()
        analyzer = null
    }
}
