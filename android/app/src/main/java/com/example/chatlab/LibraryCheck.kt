package com.example.chatlab

import java.io.Reader

/**
 * Checks the compact store against the JSON library, without changing anything: every photo of the JSON
 * must have a row, and its vector must be exactly the stored one. Only counts are reported.
 */
object LibraryCheck {
    class Report(val checked: Int, val missing: Int, val different: Int, val duplicates: Int)

    /** The best [k] photos of the JSON library for a query, read as a stream: nothing is kept but the top. */
    fun rank(reader: Reader, query: FloatArray, k: Int): List<Pair<String, Float>> {
        val top = TopK<String>(k)
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            if (seen.add(record.address)) top.offer(record.address, Library.dot(query, record.vector))
        }
        return top.sorted()
    }

    /** How many of the addresses are in both lists. */
    fun common(a: List<String>, b: List<String>): Int = a.toSet().intersect(b.toSet()).size

    fun run(reader: Reader, rows: Map<String, Long>, readVector: (Long) -> FloatArray): Report {
        var checked = 0
        var missing = 0
        var different = 0
        var duplicates = 0
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            // A photo written twice in the old JSON: the store keeps one, so only the first is compared.
            if (!seen.add(record.address)) {
                duplicates++
                return@forEachEntry
            }
            checked++
            val row = rows[record.address]
            if (row == null) {
                missing++
            } else if (!readVector(row).contentEquals(record.vector)) {
                different++
            }
        }
        return Report(checked, missing, different, duplicates)
    }
}
