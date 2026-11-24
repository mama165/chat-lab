package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerGuardTest {
    @Test
    fun enoughMemoryKeepsAllWorkers() {
        val guard = WorkerGuard(initial = 4)
        assertNull(guard.observe(now = 0, availMb = 1500, lowMemory = false))
        assertEquals(4, guard.limit)
        assertEquals(0, guard.reductions)
    }

    @Test
    fun lowMemoryLowersOneWorker() {
        val guard = WorkerGuard(initial = 4)
        assertEquals(3, guard.observe(now = 0, availMb = 300, lowMemory = false))
        assertEquals(3, guard.limit)
        assertEquals(1, guard.reductions)
    }

    @Test
    fun androidLowMemoryFlagLowersToo() {
        val guard = WorkerGuard(initial = 4)
        assertEquals(3, guard.observe(now = 0, availMb = 2000, lowMemory = true))
    }

    @Test
    fun trimRequestLowersToo() {
        val guard = WorkerGuard(initial = 4)
        assertEquals(3, guard.observe(now = 0, availMb = 2000, lowMemory = false, pressure = true))
    }

    @Test
    fun cooldownPreventsTwoReductionsAtOnce() {
        val guard = WorkerGuard(initial = 4, cooldownMs = 20_000)
        assertEquals(3, guard.observe(now = 0, availMb = 0, lowMemory = true))
        assertNull(guard.observe(now = 2_000, availMb = 0, lowMemory = true))
        assertNull(guard.observe(now = 19_999, availMb = 0, lowMemory = true))
        assertEquals(2, guard.observe(now = 20_000, availMb = 0, lowMemory = true))
        assertEquals(2, guard.reductions)
    }

    @Test
    fun neverGoesBelowOneWorker() {
        val guard = WorkerGuard(initial = 2, cooldownMs = 0)
        assertEquals(1, guard.observe(now = 0, availMb = 0, lowMemory = true))
        assertNull(guard.observe(now = 1, availMb = 0, lowMemory = true))
        assertEquals(1, guard.limit)
    }

    @Test
    fun aSingleWorkerIsNeverReduced() {
        val guard = WorkerGuard(initial = 1)
        assertNull(guard.observe(now = 0, availMb = 0, lowMemory = true))
        assertEquals(1, guard.limit)
    }
}
