package com.example.chatlab

import org.json.JSONObject

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

    /** Every stored photo, in the shape of library.json, with its vector. Read by the Photos page and the analysis. */
    fun records(each: (JSONObject, FloatArray) -> Unit) {
        database.records { item, row -> each(item, vector(row)) }
    }

    /** The row of every stored photo, by address. */
    fun rows(): Map<String, Long> = database.rowsByAddress()

    /**
     * Every stored photo as a library entry, with its vector: what the search screen reads, in place of
     * library.json. Read in one pass, with the vectors from the vector file.
     */
    fun loadAll(): List<LibraryEntry> {
        val rows = database.rowsByAddress()
        return database.entries(rows.keys.toList()).map { entry ->
            entry.copy(vector = rows[entry.id]?.let { vector(it) })
        }
    }

    /** The best [k] rows for a query, read from the vector file. */
    fun topRows(query: FloatArray, k: Int): List<Pair<Long, Float>> =
        VectorRanking.topRows(query, database.nextFreeRow(), vectors, k)

    /** The addresses of the photos matching the words, best first. */
    fun searchAddresses(search: WordSearch.Query): List<String> = database.searchAddresses(search)

    /** The photos with these addresses, read from the table. */
    fun entries(addresses: List<String>): List<LibraryEntry> = database.entries(addresses)

    /** The vector stored at a row. */
    fun vector(row: Long): FloatArray = vectors.read(row)

    fun close() = database.close()
}
