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
class CompactLibrary(context: Context) {
    private val database = PhotoDatabase(context)
    private val vectors = VectorFile(File(context.filesDir, "vectors.bin"))

    fun add(records: List<PhotoRecord>) {
        // One record per address: the newest one wins, as in the JSON writer.
        val unique = distinctById(records, { it.address }, keepLast = true)
        if (unique.isEmpty()) return
        val (assignments, _) = RowPlanner.plan(
            unique.map { it.address },
            database.rowsByAddress(),
            database.nextFreeRow(),
        )
        vectors.writeAll(assignments.mapIndexed { index, a -> a.row to unique[index].vector })
        database.upsertAll(unique.mapIndexed { index, record -> record to assignments[index].row })
    }

    fun count(): Long = database.rowsByAddress().size.toLong()
}
