package com.example.chatlab

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The library (library.json) lives in the chatlab folder you chose: it survives an
 * uninstall or a reinstall of the application. Without a usable folder, it stays
 * in the private space of the application.
 */
object LibraryStore {
    private const val NAME = "library.json"

    /** The trace of the last crash, in the diagnostics folder. */
    const val CRASH_REPORT = "plantage.txt"

    private fun folder(context: Context): DocumentFile? {
        val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
            ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canWrite() }
    }

    /** The backup of the library (ChatLab/snapshots/library.json) as a stream; null if there is none yet. */
    fun open(context: Context): java.io.InputStream? =
        LibraryExport.snapshotFile(context).takeIf { it.exists() }?.inputStream()

    /** Writes the trace of a crash to plantage.txt, in the diagnostics folder of the chatlab folder, so it can be read. */
    fun saveErrorReport(context: Context, text: String) {
        try {
            val chatlab = folder(context) ?: return
            val diagnostics = chatlab.findFile("diagnostics")?.takeIf { it.isDirectory }
                ?: chatlab.createDirectory("diagnostics")
                ?: return
            val target = diagnostics.findFile(CRASH_REPORT) ?: diagnostics.createFile("text/plain", CRASH_REPORT) ?: return
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            // Nothing more to do: we do not want to cause a second crash.
        }
    }

}

/**
 * Tout effacer: the library, in the chatlab folder and in the private space.
 * The photos, the models and the chosen folders are not touched.
 */
fun LibraryStore.wipeAnalysis(context: Context) {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    val folder = uri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
    for (name in listOf("library.json")) {
        try {
            folder?.findFile(name)?.delete()
        } catch (e: Exception) {
            // A file already missing or read-only does not block the rest.
        }
        File(context.filesDir, name).delete()
    }
    synchronized(CompactLibrary.lock) { AnalysisRecordFiles.wipe(context.filesDir) }
}
