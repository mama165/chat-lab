package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class AnalysisControlTest {
    @Test
    fun startsRunningAndCheckpointPasses() {
        val control = AnalysisControl()
        assertEquals(RunState.RUNNING, control.state)
        control.checkpoint()
    }

    @Test
    fun pauseAndResumeChangeTheState() {
        val changes = Collections.synchronizedList(ArrayList<RunState>())
        val control = AnalysisControl { changes += it }
        assertTrue(control.pause())
        assertEquals(RunState.PAUSED, control.state)
        assertFalse(control.pause())
        assertTrue(control.resume())
        assertFalse(control.resume())
        assertEquals(RunState.RUNNING, control.state)
        assertEquals(listOf(RunState.PAUSED, RunState.RUNNING), changes)
    }

    @Test
    fun stopIsFinal() {
        val control = AnalysisControl()
        assertTrue(control.stop())
        assertFalse(control.stop())
        assertFalse(control.pause())
        assertFalse(control.resume())
        assertEquals(RunState.STOPPING, control.state)
    }

    @Test(expected = AnalysisStopped::class)
    fun checkpointThrowsOnceStopped() {
        val control = AnalysisControl()
        control.stop()
        control.checkpoint()
    }

    @Test
    fun pausedWorkerWaitsUntilResume() {
        val control = AnalysisControl()
        control.pause()
        val reached = CountDownLatch(1)
        val passed = AtomicBoolean(false)
        val worker = Thread {
            reached.countDown()
            control.checkpoint()
            passed.set(true)
        }
        worker.start()
        assertTrue(reached.await(1, TimeUnit.SECONDS))
        Thread.sleep(150)
        assertFalse("le travailleur doit rester figé pendant la pause", passed.get())
        control.resume()
        worker.join(2000)
        assertTrue(passed.get())
    }

    @Test
    fun stopWakesAPausedWorker() {
        val control = AnalysisControl()
        control.pause()
        val stopped = AtomicBoolean(false)
        val worker = Thread {
            try {
                control.checkpoint()
            } catch (e: AnalysisStopped) {
                stopped.set(true)
            }
        }
        worker.start()
        Thread.sleep(100)
        control.stop()
        worker.join(2000)
        assertTrue(stopped.get())
    }

    @Test
    fun severalWorkersAllWaitAndAllResume() {
        val control = AnalysisControl()
        control.pause()
        val passed = AtomicInteger(0)
        val workers = List(3) { Thread { control.checkpoint(); passed.incrementAndGet() } }
        workers.forEach { it.start() }
        Thread.sleep(150)
        assertEquals(0, passed.get())
        control.resume()
        workers.forEach { it.join(2000) }
        assertEquals(3, passed.get())
    }
}
