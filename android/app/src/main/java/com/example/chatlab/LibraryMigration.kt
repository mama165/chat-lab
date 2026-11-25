package com.example.chatlab

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.Reader

/**
 * Copies the JSON library into the compact store, once. The JSON file is only read, never changed, and
 * the copy can be run again: a photo already copied is updated, not added twice.
 */
object LibraryMigration {
    class Report(val migrated: Int, val skipped: Int)

    /** Reads the array one entry at a time, and writes the records by batches. */
    fun run(reader: Reader, sink: RecordSink, now: Long, batchSize: Int = 200): Report {
        val batch = ArrayList<PhotoRecord>()
        var migrated = 0
        var skipped = 0
        KnownSummary.forEachEntry(reader) { item: JSONObject ->
            val record = PhotoRecordFromJson.from(item, now)
            if (record == null) {
                skipped++
            } else {
                batch += record
                if (batch.size >= batchSize) {
                    sink.add(batch.toList())
                    migrated += batch.size
                    batch.clear()
                }
            }
        }
        if (batch.isNotEmpty()) {
            sink.add(batch.toList())
            migrated += batch.size
        }
        return Report(migrated, skipped)
    }

    /** Compares the store with the JSON library, and writes the counts to the journal. */
    private fun check(context: Context, store: CompactLibrary) {
        val input = LibraryStore.open(context) ?: return
        val rows = store.rows()
        input.bufferedReader(Charsets.UTF_8).use { reader ->
            val report = LibraryCheck.run(reader, rows, store::vector)
            Diagnostics.event(
                context,
                "readCheck",
                JSONObject()
                    .put("checked", report.checked)
                    .put("missing", report.missing)
                    .put("different", report.different)
                    .put("duplicates", report.duplicates),
            )
        }
        if (rows.isEmpty()) return
        // The word search, on one word of the library: the same photos, in the same order, on both sides.
        val word = LibraryStore.open(context)?.bufferedReader(Charsets.UTF_8)?.use { LibraryCheck.firstWord(it) }
        if (word != null) {
            val words = listOf(word)
            val fromJson = LibraryStore.open(context)?.bufferedReader(Charsets.UTF_8)?.use {
                LibraryCheck.wordMatches(it, words, Place.ALL)
            }.orEmpty()
            val fromStore = store.searchAddresses(WordSearch.query(words, Place.ALL))
            // The word itself is not written: it comes from the text of the photos.
            Diagnostics.event(
                context,
                "wordCheck",
                JSONObject()
                    .put("json", fromJson.size)
                    .put("store", fromStore.size)
                    .put("same", fromJson == fromStore),
            )
        }
        // The same query on both sides: the best photos must be the same ones.
        val k = 20
        val query = store.vector(rows.values.first())
        val addressByRow = rows.entries.associate { it.value to it.key }
        val fromStore = store.topRows(query, k).mapNotNull { addressByRow[it.first] }
        val fromJson = LibraryStore.open(context)?.bufferedReader(Charsets.UTF_8)?.use { reader ->
            LibraryCheck.rank(reader, query, k).map { it.first }
        }.orEmpty()
        Diagnostics.event(
            context,
            "rankCheck",
            JSONObject()
                .put("k", k)
                .put("common", LibraryCheck.common(fromJson, fromStore))
                .put("storeCount", fromStore.size)
                .put("jsonCount", fromJson.size),
        )
    }

    /**
     * Starts the copy in the background, if it has not been done yet. The marker is written only at the
     * end, so an interrupted copy is started again. Nothing happens when there is no library file.
     */
    fun startIfNeeded(context: Context) {
        val marker = File(context.filesDir, "photos.migrated")
        Thread {
            try {
                if (marker.exists()) {
                    // Already copied: only the check runs, to see that the store still matches the JSON.
                    val compact = CompactLibrary(context)
                    try {
                        check(context, compact)
                    } finally {
                        compact.close()
                    }
                    return@Thread
                }
                val input = LibraryStore.open(context) ?: return@Thread
                val store = CompactLibrary(context)
                try {
                    input.bufferedReader(Charsets.UTF_8).use { reader ->
                        val report = run(reader, store, System.currentTimeMillis())
                        marker.writeText("migrated=${report.migrated} skipped=${report.skipped}")
                        check(context, store)
                        Diagnostics.event(
                            context,
                            "migration",
                            JSONObject().put("migrated", report.migrated).put("skipped", report.skipped),
                        )
                    }
                } finally {
                    store.close()
                }
            } catch (e: Exception) {
                Diagnostics.event(context, "migrationError", JSONObject().put("error", e.javaClass.simpleName))
            }
        }.start()
    }
}
