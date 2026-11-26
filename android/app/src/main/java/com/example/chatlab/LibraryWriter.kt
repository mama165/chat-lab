package com.example.chatlab

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes the library (library.json). Needs no model: it can be called without analysing anything. */
object LibraryWriter {
    /**
     * Saves the results of an analysis in the compact store. library.json is no longer written here: it is
     * only produced on request, by [LibraryExport], as a backup. The photos of the folders removed from the
     * list stay in the store, as they are.
     */
    fun write(context: Context, results: List<ImageResult>, keep: List<JSONObject> = emptyList()): File {
        writeCompact(context, results)
        return File(context.filesDir, "library.json")
    }

    /**
     * One batch of analysed photos, from the writing task: saved, then given to the search at once.
     * Nothing is read back from the whole library.
     */
    fun writeBatch(context: Context, results: List<ImageResult>) {
        if (results.isEmpty()) return
        val started = System.currentTimeMillis()
        val mapped = PhotoRecordMapper.map(results, started)
        val store = CompactLibrary(context)
        try {
            store.add(mapped.records)
            LiveLibrary.publish(store.entriesWithVectors(mapped.records.map { it.address }))
        } finally {
            store.close()
        }
        Diagnostics.event(
            context,
            "compactWritten",
            JSONObject().put("photos", mapped.records.size).put("ms", System.currentTimeMillis() - started),
        )
    }

    /**
     * The same results, in the compact store (parallel writing: the JSON is still the one the app reads).
     * A failure is recorded in the journal and never stops the analysis.
     */
    private fun writeCompact(context: Context, results: List<ImageResult>) {
        try {
            val started = System.currentTimeMillis()
            val mapped = PhotoRecordMapper.map(results, started)
            val store = CompactLibrary(context)
            try {
                store.add(mapped.records)
            } finally {
                store.close()
            }
            // The cost of the parallel write, measured in every save of a normal analysis.
            Diagnostics.event(
                context,
                "compactWritten",
                JSONObject().put("photos", mapped.records.size).put("ms", System.currentTimeMillis() - started),
            )
            if (mapped.withoutAddress > 0) {
                Diagnostics.event(context, "compactSkipped", JSONObject().put("withoutAddress", mapped.withoutAddress))
            }
        } catch (e: Exception) {
            Diagnostics.event(context, "compactError", JSONObject().put("error", e.javaClass.simpleName))
        }
    }
}
