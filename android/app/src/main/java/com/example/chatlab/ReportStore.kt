package com.example.chatlab

import android.content.Context
import java.io.File

/** Le dernier compte rendu d'analyse et sa date, gardés dans l'espace privé de l'application. */
object ReportStore {
    private const val FILE = "compte_rendu.txt"
    private const val KEY_DATE = "last_analysis"

    fun save(context: Context, report: String, at: Long = System.currentTimeMillis()) {
        File(context.filesDir, FILE).writeText(report, Charsets.UTF_8)
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().putLong(KEY_DATE, at).apply()
    }

    fun read(context: Context): String? =
        File(context.filesDir, FILE).takeIf { it.exists() }?.readText(Charsets.UTF_8)?.takeIf { it.isNotBlank() }

    /** Date de la dernière analyse, ou null s'il n'y en a pas eu. */
    fun lastDate(context: Context): SimpleDate? {
        val millis = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getLong(KEY_DATE, 0L)
        return if (millis > 0) SimpleDate.of(millis) else null
    }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().remove(KEY_DATE).apply()
    }
}
