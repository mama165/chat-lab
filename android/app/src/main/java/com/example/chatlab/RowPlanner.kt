package com.example.chatlab

/** The row given to one photo in vectors.bin, and in the photo table. */
data class RowAssignment(val address: String, val row: Long)

/**
 * Gives each photo its row. A photo already known keeps its row, so its vector is overwritten in place
 * and no row is left empty; a new photo takes the next free row. [addresses] must be unique.
 */
object RowPlanner {
    fun plan(addresses: List<String>, existingRows: Map<String, Long>, nextFree: Long): Pair<List<RowAssignment>, Long> {
        var next = nextFree
        val assignments = addresses.map { address ->
            val row = existingRows[address] ?: next++
            RowAssignment(address, row)
        }
        return assignments to next
    }
}
