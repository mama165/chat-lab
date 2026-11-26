package com.example.chatlab

import android.content.Context
import android.os.Environment
import org.json.JSONArray
import java.io.File
import java.io.Writer
import org.json.JSONObject

/**
 * Writes the backup of the library, library.json, from the compact store, on request only. The backup
 * goes to ChatLab/snapshots, beside the diagnostics. It is written in a temporary file first, which replaces
 * the backup only once complete: an export that fails leaves the previous backup as it was.
 * Nothing reads it back in the app except the one-time copy at a restore.
 */
object LibraryExport {
    private const val NAME = "library.json"

    /** ChatLab/snapshots on the shared storage; the app's own storage when the all-files access is not granted. */
    fun snapshotDir(context: Context): File =
        if (StorageAccess.granted()) File(Environment.getExternalStorageDirectory(), "ChatLab/snapshots")
        else File(context.filesDir, "snapshots")

    fun snapshotFile(context: Context): File = File(snapshotDir(context), NAME)

    /** Returns the number of photos written. */
    fun run(context: Context): Int {
        val store = CompactLibrary(context)
        var count = 0
        try {
            replaceAtomically(snapshotDir(context), NAME) { out ->
                count = writeEntries(out) { emit -> store.records(emit) }
            }
            return count
        } finally {
            store.close()
        }
    }

    /**
     * Writes [body] into a temporary file beside [name], then replaces [name] with it. If [body] fails, the
     * previous file stays as it was and the temporary file is removed.
     */
    internal fun replaceAtomically(dir: File, name: String, body: (Writer) -> Unit) {
        dir.mkdirs()
        val temp = File(dir, "$name.tmp")
        val target = File(dir, name)
        try {
            temp.bufferedWriter(Charsets.UTF_8).use(body)
            if (!temp.renameTo(target)) error("remplacement impossible de $name")
        } catch (e: Exception) {
            temp.delete()
            throw e
        }
    }

    /** The library as a JSON array, one photo per entry, with its vector. Returns the number of photos. */
    internal fun writeEntries(out: Writer, each: ((JSONObject, FloatArray) -> Unit) -> Unit): Int {
        var count = 0
        out.write("[")
        each { item, vector ->
            if (count > 0) out.write(",")
            item.put("vectors", JSONArray().put(JSONArray(vector.toList())))
            out.write(item.toString())
            count++
        }
        out.write("]")
        return count
    }
}
