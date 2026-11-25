package com.example.chatlab

import java.io.Reader

/**
 * Checks the compact store against the JSON library, without changing anything: every photo of the JSON
 * must have a row, and its vector must be exactly the stored one. Only counts are reported.
 */
object LibraryCheck {
    class Report(val checked: Int, val missing: Int, val different: Int, val duplicates: Int)

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
