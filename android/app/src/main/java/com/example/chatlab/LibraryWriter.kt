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
    fun write(context: Context, results: List<ImageResult>, keep: List<JSONObject> = emptyList()): File {
        val added = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val fresh = ArrayList<Pair<String, JSONObject>>()
        for (r in results) {
            if (r.error != null || r.vector == null) continue
            // Two folders can contain the same file name: the original address is unique.
            val id = if (r.source.isNotBlank()) r.source else r.file.name
            val entry = JSONObject()
                .put("id", id)
                .put("path", if (r.source.isNotBlank()) r.source else r.file.path)
                .put("name", r.file.name)
                .put("kind", "image")
                .put("analysis_version", ANALYSIS_VERSION)
                .put("size", if (r.source.isNotBlank()) r.size else r.file.length())
                .put("added_at", added.format(Date(if (r.modified > 0) r.modified else r.file.lastModified())))
                .put("faces", r.faces)
                .put("portrait", r.portrait)
                .put("vectors", JSONArray().put(JSONArray(r.vector.toList())))
            r.indoor?.let { entry.put("indoor", it) }
            if (r.text.isNotBlank()) entry.put("text", r.text)
            if (r.source.isNotBlank()) {
                entry.put("source", r.source)
                entry.put("modified", r.modified)
            }
            if (r.taken.isNotBlank()) entry.put("taken", r.taken)
            if (r.takenMs > 0) entry.put("taken_ms", r.takenMs)
            if (r.folder.isNotBlank()) entry.put("folder", r.folder)
            if (r.mime.isNotBlank()) entry.put("mime", r.mime)
            fresh += id to entry
        }
        // The photos of the folders removed from the list stay in the library, as they are.
        // A photo is written once: the newest record of an address wins, so that a photo analysed
        // twice (two selected folders that overlap, or a second run) never gets two records.
        val all = distinctById(keep.map { it.optString("id") to it } + fresh, { it.first }, keepLast = true)
        // The library goes into the chosen chatlab folder (it survives a reinstall), entry by entry.
        LibraryStore.writeStreamed(context) { out ->
            out.write("[")
            all.forEachIndexed { index, pair ->
                if (index > 0) out.write(",")
                out.write(pair.second.toString())
            }
            out.write("]")
        }
        writeCompact(context, results)
        return File(context.filesDir, "library.json")
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
