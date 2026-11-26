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
    private const val REMOVALS = "retraits.json"

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

    /** The removals: for each search, the photos set aside. */
    fun readRemovals(context: Context): JSONObject {
        val text = try {
            val inFolder = folder(context)?.findFile(REMOVALS)
            if (inFolder != null && inFolder.exists()) {
                context.contentResolver.openInputStream(inFolder.uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
            } else {
                File(context.filesDir, REMOVALS).takeIf { it.exists() }?.readText(Charsets.UTF_8)
            }
        } catch (e: Exception) {
            null
        }
        return try {
            JSONObject(text ?: "{}")
        } catch (e: Exception) {
            JSONObject()
        }
    }

    /** Sets a photo aside for a given search; the removal is kept from one launch to the next. */
    fun addRemoval(context: Context, query: String, source: String) {
        val all = readRemovals(context)
        val list = all.optJSONArray(query) ?: JSONArray().also { all.put(query, it) }
        list.put(source)
        val text = all.toString()
        val dir = folder(context)
        if (dir == null) {
            File(context.filesDir, REMOVALS).writeText(text, Charsets.UTF_8)
            return
        }
        val target = dir.findFile(REMOVALS) ?: dir.createFile("application/json", REMOVALS)
            ?: error("impossible de créer retraits.json dans le dossier chatlab")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("impossible d'écrire retraits.json")
    }

    /** Writes all the removals, in the chatlab folder if possible. */
    fun writeRemovals(context: Context, json: JSONObject) {
        val text = json.toString()
        val dir = folder(context)
        if (dir == null) {
            File(context.filesDir, REMOVALS).writeText(text, Charsets.UTF_8)
            return
        }
        val target = dir.findFile(REMOVALS) ?: dir.createFile("application/json", REMOVALS)
            ?: error("impossible de créer retraits.json dans le dossier chatlab")
        context.contentResolver.openOutputStream(target.uri, "wt")?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
            ?: error("impossible d'écrire retraits.json")
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
 * Tout effacer: the library and the removals, in the chatlab folder and in the private space.
 * The photos, the models and the chosen folders are not touched.
 */
fun LibraryStore.wipeAnalysis(context: Context) {
    val uri = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(KEY_FOLDER, null)
    val folder = uri?.let { DocumentFile.fromTreeUri(context, Uri.parse(it)) }
    for (name in listOf("library.json", "retraits.json")) {
        try {
            folder?.findFile(name)?.delete()
        } catch (e: Exception) {
            // A file already missing or read-only does not block the rest.
        }
        File(context.filesDir, name).delete()
    }
    AnalysisRecordFiles.wipe(context.filesDir)
}
