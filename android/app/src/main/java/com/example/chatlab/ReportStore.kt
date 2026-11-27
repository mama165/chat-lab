package com.example.chatlab

import android.content.Context
import java.io.File

/** The last analysis report and its date, kept in the private space of the application. */
object ReportStore {
    private const val FILE = "compte_rendu.txt"
    private const val KEY_DATE = "last_analysis"

    fun save(context: Context, report: String, at: Long = System.currentTimeMillis()) {
        File(context.filesDir, FILE).writeText(report, Charsets.UTF_8)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putLong(KEY_DATE, at).apply()
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    /** Date of the last analysis, or null if there was none. */
    fun lastDate(context: Context): SimpleDate? = lastMillis(context)?.let { SimpleDate.of(it) }

    /** Moment of the last analysis in milliseconds, or null if there was none. */
    fun lastMillis(context: Context): Long? {
        val millis = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(KEY_DATE, 0L)
        return if (millis > 0) millis else null
    }

    private const val RUN_FILE = "derniere_analyse.json"

    /** Saves the report of an analysis (per folder) and its text report. */
    fun saveRun(context: Context, report: RunReport) {
        File(context.filesDir, RUN_FILE).writeText(report.toJson(), Charsets.UTF_8)
        save(context, report.toText(), report.finishedAt)
    }

    /** The report of the last analysis, or null if there is none. */
    fun readRun(context: Context): RunReport? =
        RunReport.fromJson(File(context.filesDir, RUN_FILE).takeIf { it.exists() }?.readText(Charsets.UTF_8))

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        File(context.filesDir, RUN_FILE).delete()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_DATE).apply()
    }
}
