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

    private fun folder(context: Context): DocumentFile? {
        val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
            ?: return null
        return DocumentFile.fromTreeUri(context, Uri.parse(uri))?.takeIf { it.canWrite() }
    }

    /** The backup of the library (ChatLab/snapshots/library.json) as a stream; null if there is none yet. */
    fun open(context: Context): java.io.InputStream? =
        LibraryExport.snapshotFile(context).takeIf { it.exists() }?.inputStream()

    /** Writes the trace of a crash to erreur.txt, in the chatlab folder, so it can be read. */
    fun saveErrorReport(context: Context, text: String) {
        try {
            val dir = folder(context) ?: return
            val target = dir.findFile("erreur.txt") ?: dir.createFile("text/plain", "erreur.txt") ?: return
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        } catch (e: Exception) {
            // Nothing more to do: we do not want to cause a second crash.
        }
    }

}

/** The saved library, read from the chatlab folder (or the private space otherwise). */

/** Size of the saved library, in bytes (0 if it does not exist). */
fun LibraryStore.librarySize(context: Context): Long = AnalysisRecordFiles.size(context.filesDir) + savedLibrarySize(context)

/** Size of the JSON copy of the library, the backup kept in the chatlab folder (0 if it does not exist). */
private fun savedLibrarySize(context: Context): Long = try {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    val inFolder = uri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it))?.findFile("library.json") }
    inFolder?.length() ?: File(context.filesDir, "library.json").takeIf { it.exists() }?.length() ?: 0L
} catch (e: Exception) {
    0L
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
