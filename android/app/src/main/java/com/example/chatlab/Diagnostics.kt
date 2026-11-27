package com.example.chatlab

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A technical log that survives a sudden death of the app. Each line is one JSON object,
 * written and flushed to disk at once, in ChatLab/diagnostics (or in the app's private
 * folder if the phone storage is not granted).
 *
 * It records, for each run: the device, the level and workers, a snapshot of the memory,
 * the CPU and the thermal state every few seconds, low-memory signals, uncaught errors, and
 * the reason Android gave for the last ends of the app (out of memory, native crash, ANR).
 */
object Diagnostics {
    private const val MB = 1024L * 1024L
    private const val PREFS = "diagnostics"
    private const val KEY_LAST_EXIT = "lastExitSeen"
    private const val KEY_CURRENT_RUN = "currentRun"
    private val lock = Any()
    private val started = System.currentTimeMillis()
    private var installed = false
    private var current: File? = null

    private fun dir(context: Context): File =
        if (StorageAccess.granted()) File(Environment.getExternalStorageDirectory(), "ChatLab/diagnostics")
        else File(context.filesDir, "diagnostics")

    /** Called when the app starts (activity or service): installs the crash hook, reads the last exits. */
    fun onAppStart(context: Context) {
        synchronized(lock) {
            if (!installed) {
                installed = true
                val previous = Thread.getDefaultUncaughtExceptionHandler()
                Thread.setDefaultUncaughtExceptionHandler { thread, error ->
                    runCatching {
                        event(
                            context,
                            "uncaught",
                            JSONObject()
                                .put("thread", thread.name)
                                .put("error", error.toString())
                                .put("stack", Log.getStackTraceString(error).take(20_000)),
                        )
                    }
                    previous?.uncaughtException(thread, error)
                }
            }
        }
        runCatching { readExits(context) }
        event(context, "appStart", device(context))
    }

    /** Starts a new run file; its name is kept, so that a later exit is written in it. */
    fun openRun(context: Context, level: AnalysisLevel, folders: Int): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir(context), "run-$stamp.jsonl")
        synchronized(lock) { current = file }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_CURRENT_RUN, file.path).apply()
        event(
            context,
            "run",
            JSONObject()
                .put("level", level.key)
                .put("workers", level.workers)
                .put("folders", folders),
        )
        return file.name
    }

    /** Native memory now, in megabytes. */
    fun nativeMb(): Long = android.os.Debug.getNativeHeapAllocatedSize() / (1024 * 1024)

    /**
     * Runs [block] and writes a "nativeStep" event when the native memory moved by 100 MB or more during it,
     * so that the journal shows which step made the jump.
     */
    fun <T> native(context: Context, stage: String, block: () -> T): T {
        val before = nativeMb()
        try {
            return block()
        } finally {
            val after = nativeMb()
            if (kotlin.math.abs(after - before) >= 100) {
                event(
                    context,
                    "nativeStep",
                    JSONObject().put("stage", stage).put("beforeMb", before).put("afterMb", after),
                )
            }
        }
    }

    /** Writes one event, with the time since the app started. Never throws. */
    fun event(context: Context, kind: String, data: JSONObject = JSONObject()) {
        runCatching {
            val line = JSONObject()
                .put("at", System.currentTimeMillis())
                .put("sinceStartMs", System.currentTimeMillis() - started)
                .put("kind", kind)
                .put("data", data)
                .toString()
            val file = synchronized(lock) { current } ?: File(dir(context), "session.jsonl")
            file.parentFile?.mkdirs()
            synchronized(lock) {
                FileOutputStream(file, true).use { out ->
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }
        }
    }

    /** A snapshot of the memory, the CPU and the thermal state, as one JSON object. */
    fun snapshot(context: Context): JSONObject = runCatching {
        val am = context.getSystemService(ActivityManager::class.java)
        val info = ActivityManager.MemoryInfo()
        am?.getMemoryInfo(info)
        val pss = Debug.MemoryInfo()
        Debug.getMemoryInfo(pss)
        val runtime = Runtime.getRuntime()
        val thermal = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: -1
        } else -1
        JSONObject()
            .put("cpuMs", Process.getElapsedCpuTime())
            .put("sysAvailMb", info.availMem / MB)
            .put("sysTotalMb", info.totalMem / MB)
            .put("sysLowMemory", info.lowMemory)
            .put("sysThresholdMb", info.threshold / MB)
            .put("pssMb", pss.totalPss / 1024)
            .put("nativeUsedMb", Debug.getNativeHeapAllocatedSize() / MB)
            .put("nativeSizeMb", Debug.getNativeHeapSize() / MB)
            .put("javaUsedMb", (runtime.totalMemory() - runtime.freeMemory()) / MB)
            .put("javaMaxMb", runtime.maxMemory() / MB)
            .put("thermal", thermal)
    }.getOrElse { JSONObject().put("snapshotError", it.toString()) }

    private fun device(context: Context): JSONObject = JSONObject()
        .put("model", Build.MODEL)
        .put("manufacturer", Build.MANUFACTURER)
        .put("android", Build.VERSION.SDK_INT)
        .put("cores", Runtime.getRuntime().availableProcessors())
        .put("appVersion", runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "")

    /**
     * The reasons Android gave for the ends of the app since the last start: out of memory,
     * native crash, not responding... Written into the current run, with the native trace if any.
     */
    private fun readExits(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        val am = context.getSystemService(ActivityManager::class.java) ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(KEY_LAST_EXIT, 0L)
        var newest = since
        for (exit in am.getHistoricalProcessExitReasons(context.packageName, 0, 10)) {
            if (exit.timestamp <= since) continue
            newest = maxOf(newest, exit.timestamp)
            val data = JSONObject()
                .put("exitAt", exit.timestamp)
                .put("reason", reasonName(exit.reason))
                .put("reasonCode", exit.reason)
                .put("status", exit.status)
                .put("importance", exit.importance)
                .put("description", exit.description ?: "")
                .put("pssKb", exit.pss)
                .put("rssKb", exit.rss)
            if (exit.reason == ApplicationExitInfo.REASON_CRASH_NATIVE || exit.reason == ApplicationExitInfo.REASON_ANR) {
                val trace = exit.traceInputStream?.use { it.bufferedReader().readText() }.orEmpty()
                val traceFile = File(dir(context), "exit-${exit.timestamp}.trace.txt")
                runCatching { traceFile.parentFile?.mkdirs(); traceFile.writeText(trace.take(400_000)) }
                data.put("trace", traceFile.path)
            }
            event(context, "previousExit", data)
        }
        prefs.edit().putLong(KEY_LAST_EXIT, newest).apply()
    }

    /** The name of an exit reason, from its code. */
    /**
     * What "Copier le résultat en cours" copies during a run: the progress as it is, the journal of the run
     * as far as it is written, and the recent lines of the application's journal. The analysis goes on.
     */
    fun inProgressText(context: Context, progress: JSONObject): String = synchronized(lock) {
        val path = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT_RUN, null)
        val run = path?.let { readLinesSafely(File(it)) }.orEmpty()
        inProgressBundle(progress, run, readLinesSafely(File(dir(context), "session.jsonl"))).toString()
    }

    /** The file name of the journal of the run in progress, or null when no run is open. */
    fun currentRunName(context: Context): String? =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_CURRENT_RUN, null)?.let { File(it).name }

    /** Where the journals are kept, as it is named to the user. */
    fun folderLabel(): String =
        if (StorageAccess.granted()) "ChatLab/diagnostics" else "stockage privé de l'app, dossier diagnostics"

    /** The bundle of a copy made during the run, from the lines already read. */
    fun inProgressBundle(progress: JSONObject, runLines: List<String>, sessionLines: List<String>): JSONObject = JSONObject()
        .put("inProgress", true)
        .put("progress", progress)
        .put(
            "diagnostics",
            JSONObject()
                .put("run", JSONArray(runLines.mapNotNull { parse(it) }))
                .put("session", JSONArray(sessionLines.takeLast(SESSION_LINES).mapNotNull { parse(it) })),
        )

    private fun readLinesSafely(file: File): List<String> =
        runCatching { file.readLines().filter { it.isNotBlank() } }.getOrDefault(emptyList())

    /** Pure choice of the journal name: the run file whose start time is nearest, within [windowMs]. */
    fun nearestRun(names: List<String>, startMs: Long, zone: TimeZone, windowMs: Long = 10 * 60_000L): String {
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).apply { timeZone = zone }
        return names
            .mapNotNull { name ->
                Regex("""run-(\d{8}-\d{6})\.jsonl""").matchEntire(name)?.let { match ->
                    runCatching { format.parse(match.groupValues[1])!!.time }.getOrNull()?.let { name to it }
                }
            }
            .filter { (_, time) -> kotlin.math.abs(time - startMs) <= windowMs }
            .minByOrNull { (_, time) -> kotlin.math.abs(time - startMs) }
            ?.first.orEmpty()
    }

    /** How many lines of the application's journal are copied: the recent ones. */
    const val SESSION_LINES = 300

    private fun parse(line: String): JSONObject? = runCatching { JSONObject(line) }.getOrNull()

    fun reasonName(code: Int): String = when (code) {
        ApplicationExitInfo.REASON_UNKNOWN -> "UNKNOWN"
        ApplicationExitInfo.REASON_EXIT_SELF -> "EXIT_SELF"
        ApplicationExitInfo.REASON_SIGNALED -> "SIGNALED"
        ApplicationExitInfo.REASON_LOW_MEMORY -> "LOW_MEMORY"
        ApplicationExitInfo.REASON_CRASH -> "CRASH"
        ApplicationExitInfo.REASON_CRASH_NATIVE -> "CRASH_NATIVE"
        ApplicationExitInfo.REASON_ANR -> "ANR"
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "INITIALIZATION_FAILURE"
        ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "PERMISSION_CHANGE"
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "EXCESSIVE_RESOURCE_USAGE"
        ApplicationExitInfo.REASON_USER_REQUESTED -> "USER_REQUESTED"
        ApplicationExitInfo.REASON_USER_STOPPED -> "USER_STOPPED"
        ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "DEPENDENCY_DIED"
        ApplicationExitInfo.REASON_OTHER -> "OTHER"
        ApplicationExitInfo.REASON_FREEZER -> "FREEZER"
        else -> "CODE_$code"
    }
}
