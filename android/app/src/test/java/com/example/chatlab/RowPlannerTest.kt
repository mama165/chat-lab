package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class RowPlannerTest {
    @Test
    fun newPhotosTakeTheNextFreeRowsInOrder() {
        val (rows, next) = RowPlanner.plan(listOf("a", "b", "c"), emptyMap(), nextFree = 5)
        assertEquals(listOf(RowAssignment("a", 5), RowAssignment("b", 6), RowAssignment("c", 7)), rows)
        assertEquals(8L, next)
    }

    @Test
    fun aKnownPhotoKeepsItsRowAndDoesNotMoveTheNextFreeOne() {
        val (rows, next) = RowPlanner.plan(listOf("a"), mapOf("a" to 2L), nextFree = 10)
        assertEquals(listOf(RowAssignment("a", 2)), rows)
        assertEquals(10L, next)
    }

    @Test
    fun aMixedBatchKeepsKnownRowsAndNumbersTheNewOnes() {
        val (rows, next) = RowPlanner.plan(listOf("new1", "old", "new2"), mapOf("old" to 4L), nextFree = 7)
        assertEquals(
            listOf(RowAssignment("new1", 7), RowAssignment("old", 4), RowAssignment("new2", 8)),
            rows,
        )
        assertEquals(9L, next)
    }

    @Test
    fun anEmptyBatchChangesNothing() {
        val (rows, next) = RowPlanner.plan(emptyList(), mapOf("a" to 0L), nextFree = 1)
        assertEquals(emptyList<RowAssignment>(), rows)
        assertEquals(1L, next)
    }
}
