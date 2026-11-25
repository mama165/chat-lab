package com.example.chatlab

import android.content.Context
import java.io.File

/**
 * Writes the photos in the order that keeps them consistent if the app stops in the middle:
 * 1. the vectors go to vectors.bin, at the rows planned, and are forced to the disk;
 * 2. only then the rows go to the table, in one transaction.
 * A photo exists when its row exists. Vectors written without a row are harmless: no row points to them,
 * and the next write takes the same place.
 */
/** Where photo records go: the compact store, or a test. */
fun interface RecordSink {
    fun add(records: List<PhotoRecord>)
}

class CompactLibrary(context: Context) : RecordSink {
    private val database = PhotoDatabase(context)
    private val vectors = VectorFile(File(context.filesDir, "vectors.bin"))

    /** The analysis and the migration may write at the same time: rows are planned one writer at a time. */
    override fun add(records: List<PhotoRecord>) = synchronized(lock) {
        // One record per address: the newest one wins, as in the JSON writer.
        val unique = distinctById(records, { it.address }, keepLast = true)
        if (unique.isEmpty()) return@synchronized
        val (assignments, _) = RowPlanner.plan(
            unique.map { it.address },
            database.rowsByAddress(),
            database.nextFreeRow(),
        )
        vectors.writeAll(assignments.mapIndexed { index, a -> a.row to unique[index].vector })
        database.upsertAll(unique.mapIndexed { index, record -> record to assignments[index].row })
    }

    companion object {
        private val lock = Any()
    }

    fun count(): Long = database.rowsByAddress().size.toLong()

    /** The row of every stored photo, by address. */
    fun rows(): Map<String, Long> = database.rowsByAddress()

    /** The vector stored at a row. */
    fun vector(row: Long): FloatArray = vectors.read(row)

    fun close() = database.close()
}
