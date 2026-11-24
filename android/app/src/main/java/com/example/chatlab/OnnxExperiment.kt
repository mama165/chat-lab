package com.example.chatlab

import ai.onnxruntime.OrtSession
import android.content.Context

/**
 * An experiment on the memory of the ONNX sessions: the CPU memory arena can be switched off.
 * The arena keeps the memory a session has used, so that it can be reused; switched off, the
 * session gives back what it does not need, at a cost in speed. On by default: the experiment is
 * chosen in the test page, and it applies to the models loaded from then on.
 */
object OnnxExperiment {
    private const val KEY = "onnx_arena"
    private const val OFF = "off"

    /** Whether the arena is on, from the saved value: on unless it was switched off. */
    fun arenaFromRaw(raw: String?): Boolean = raw != OFF

    fun arenaOn(context: Context): Boolean =
        arenaFromRaw(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY, null))

    fun write(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(KEY, if (on) "on" else OFF)
            .apply()
    }

    /** Session options for one session, with the arena on or off. Close them once the session is open. */
    fun options(arenaOn: Boolean): OrtSession.SessionOptions = OrtSession.SessionOptions().apply {
        setCPUArenaAllocator(arenaOn)
    }
}
