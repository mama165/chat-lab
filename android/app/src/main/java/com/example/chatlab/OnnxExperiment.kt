package com.example.chatlab

import ai.onnxruntime.OrtSession
import android.content.Context

/**
 * Experiments on the ONNX sessions, chosen in the test page. They apply to the analyses started
 * after the change. The defaults are the ones of the app: the arena kept, and the number of
 * threads chosen by ONNX Runtime.
 */
object OnnxExperiment {
    private const val ARENA_KEY = "onnx_arena"
    private const val THREADS_KEY = "onnx_threads"
    private const val OFF = "off"

    /** The threads that can be chosen; 0 leaves the choice to ONNX Runtime. */
    val THREAD_CHOICES: List<Int> = listOf(0, 2, 4, 8)

    data class Settings(val arenaOn: Boolean = true, val threads: Int = 0)

    /** Whether the arena is on, from the saved value: on unless it was switched off. */
    fun arenaFromRaw(raw: String?): Boolean = raw != OFF

    /** The number of threads from the saved value; 0 (the default) when unknown. */
    fun threadsFromRaw(raw: String?): Int = raw?.toIntOrNull()?.takeIf { it in THREAD_CHOICES } ?: 0

    fun read(context: Context): Settings {
        val prefs = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        return Settings(
            arenaOn = arenaFromRaw(prefs.getString(ARENA_KEY, null)),
            threads = threadsFromRaw(prefs.getString(THREADS_KEY, null)),
        )
    }

    fun arenaOn(context: Context): Boolean = read(context).arenaOn

    fun writeArena(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(ARENA_KEY, if (on) "on" else OFF)
            .apply()
    }

    fun writeThreads(context: Context, threads: Int) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(THREADS_KEY, threads.toString())
            .apply()
    }

    /** Session options for one session. Close them once the session is open. */
    fun options(settings: Settings, defaultThreads: Int = 0): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(settings.arenaOn)
        // The test page chooses the threads when it is set; otherwise the analysis gives its share.
        val threads = if (settings.threads > 0) settings.threads else defaultThreads
        if (threads > 0) setIntraOpNumThreads(threads)
    }
}
