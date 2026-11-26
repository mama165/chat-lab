package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadBudgetTest {
    @Test
    fun theWorkersShareTheCoresMinusOneForTheSystem() {
        assertEquals(1, ThreadBudget.perSession(cores = 8, workers = 4))
        assertEquals(3, ThreadBudget.perSession(cores = 8, workers = 2))
        assertEquals(7, ThreadBudget.perSession(cores = 8, workers = 1))
    }

    @Test
    fun aSessionAlwaysHasAtLeastOneThread() {
        assertEquals(1, ThreadBudget.perSession(cores = 1, workers = 4))
        assertEquals(1, ThreadBudget.perSession(cores = 2, workers = 4))
    }

    @Test
    fun noWorkerIsTreatedAsOne() {
        assertEquals(7, ThreadBudget.perSession(cores = 8, workers = 0))
    }
}
