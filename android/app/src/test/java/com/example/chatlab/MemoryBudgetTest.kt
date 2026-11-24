package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MemoryBudgetTest {
    @Test
    fun theLevelChosenIsTheMostWorkersAtStartWhenMemoryIsEnough() {
        assertEquals(4, MemoryBudget.workersAllowedAtStart(levelWorkers = 4, availMb = 8000))
    }

    @Test
    fun lessMemoryMeansFewerWorkersAtStart() {
        // 3 279 Mo free, as on the phone of the failed run: two workers fit, not four.
        assertEquals(2, MemoryBudget.workersAllowedAtStart(levelWorkers = 4, availMb = 3279))
        assertEquals(3, MemoryBudget.workersAllowedAtStart(levelWorkers = 4, availMb = 4800))
    }

    @Test
    fun atLeastOneWorkerEvenWhenMemoryIsShort() {
        assertEquals(1, MemoryBudget.workersAllowedAtStart(levelWorkers = 4, availMb = 200))
        assertEquals(1, MemoryBudget.workersAllowedAtStart(levelWorkers = 4, availMb = 0))
    }

    @Test
    fun neverMoreThanTheLevelChosen() {
        assertEquals(2, MemoryBudget.workersAllowedAtStart(levelWorkers = 2, availMb = 20000))
        assertEquals(1, MemoryBudget.workersAllowedAtStart(levelWorkers = 1, availMb = 20000))
    }

    @Test
    fun theGuardReactsBelowNineHundredMegabytes() {
        val guard = WorkerGuard(initial = 4)
        assertNull(guard.observe(now = 0, availMb = 950, lowMemory = false))
        assertEquals(3, guard.observe(now = 0, availMb = 850, lowMemory = false))
    }

    @Test
    fun theFirstReductionHasNoWait() {
        val guard = WorkerGuard(initial = 4)
        assertEquals(3, guard.observe(now = 1_000, availMb = 300, lowMemory = false))
        assertEquals(2, guard.observe(now = 1_000 + 8_000, availMb = 300, lowMemory = false))
    }

    @Test
    fun theStartLimitIsKeptForTheMessage() {
        val guard = WorkerGuard(initial = 2)
        assertEquals(2, guard.startLimit)
        guard.observe(now = 0, availMb = 100, lowMemory = true)
        assertEquals(2, guard.startLimit)
        assertEquals(1, guard.limit)
    }
}
