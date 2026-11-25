package com.example.chatlab

import android.content.Context
import android.os.Environment
import java.io.File

/**
 * Writes the file of each finished analysis. On the phone, the ChatLab/analyses folder of the
 * internal storage, reachable from the Files app; without access to all files, the
 * private folder of the app. A write error must never make the analysis fail.
 */
object RunArchive {
    fun directory(context: Context): File =
        if (StorageAccess.granted()) File(Environment.getExternalStorageDirectory(), "ChatLab/analyses")
        else File(context.filesDir, "analyses")

    fun save(context: Context, record: AnalysisRecord): File? = try {
        val dir = directory(context).apply { mkdirs() }
        File(dir, AnalysisFiles.nameFor(record.startMs)).also { it.writeText(record.toJson().toString(2)) }
    } catch (e: Exception) {
        null
    }

    /** The analysis files, from the newest to the oldest. */
    fun list(context: Context): List<File> =
        directory(context).listFiles { f -> f.isFile && f.name.endsWith(".json") }
            ?.sortedByDescending { it.name }
            .orEmpty()
}
