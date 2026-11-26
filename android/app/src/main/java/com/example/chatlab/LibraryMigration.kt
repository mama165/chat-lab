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

    /**
     * Starts the copy in the background, if it has not been done yet. The marker is written only at the
     * end, so an interrupted copy is started again. Nothing happens when there is no library file.
     */
    fun startIfNeeded(context: Context) {
        val marker = File(context.filesDir, "photos.migrated")
        Thread {
            try {
                // Already copied: nothing to read from library.json.
                if (marker.exists()) return@Thread
                val input = LibraryStore.open(context) ?: return@Thread
                val store = CompactLibrary(context)
                try {
                    input.bufferedReader(Charsets.UTF_8).use { reader ->
                        val report = run(reader, store, System.currentTimeMillis())
                        marker.writeText("migrated=${report.migrated} skipped=${report.skipped}")
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
