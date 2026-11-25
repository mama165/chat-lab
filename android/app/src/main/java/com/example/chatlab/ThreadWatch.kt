package com.example.chatlab

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StrictMode
import java.util.concurrent.Executors
import org.json.JSONArray
import org.json.JSONObject

/**
 * In a debug build, StrictMode reports each file read or write, and each network call, made on the main thread.
 * Each place is written to the diagnostic journal once per run, so that a freeze of the screen leaves a trace in
 * the files the application already keeps. Nothing is shown on the screen, and a release build does nothing.
 */
object ThreadWatch {
    private val seen = HashSet<String>()
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "thread-watch").apply { isDaemon = true } }

    /** Turns the check on when the application is debuggable, that is in a debug build. */
    fun installIfDebuggable(context: Context) {
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val listener = StrictMode.OnThreadViolationListener { violation ->
            val where = ThreadViolationSite.frames(violation.stackTrace.map { it.toString() })
            if (markFirst(ThreadViolationSite.key(violation.javaClass.simpleName, where))) {
                Diagnostics.event(context, "mainThread", ThreadViolationSite.json(violation.javaClass.simpleName, where))
            }
        }
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyListener(worker, listener)
                .build(),
        )
    }

    @Synchronized
    private fun markFirst(key: String): Boolean = seen.add(key)
}

/** The lines of a violation that are worth reading: the application's own first, then a few others. */
internal object ThreadViolationSite {
    private const val APPLICATION = "com.example.chatlab"
    private const val OWN_LINES = 4
    private const val OTHER_LINES = 2

    fun frames(all: List<String>): List<String> {
        val own = all.filter { it.contains(APPLICATION) }.take(OWN_LINES)
        val others = all.filterNot { it.contains(APPLICATION) }.take(OTHER_LINES)
        return own + others
    }

    /** One place: the kind of violation and where it starts. Two violations from the same place share it. */
    fun key(kind: String, where: List<String>): String = kind + "|" + (where.firstOrNull() ?: "?")

    fun json(kind: String, where: List<String>): JSONObject =
        JSONObject().put("kind", kind).put("where", JSONArray(where))
}
