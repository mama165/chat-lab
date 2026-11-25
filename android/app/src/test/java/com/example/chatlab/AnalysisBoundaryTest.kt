package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class AnalysisBoundaryTest {
    @Test
    fun running_boundary_does_nothing() {
        val control = AnalysisControl()
        var paused = 0
        var resumed = 0
        control.boundary(onPaused = { paused++ }, onResumed = { resumed++ })
        assertEquals(0, paused)
        assertEquals(0, resumed)
    }

    @Test
    fun pause_releases_once_and_resume_reloads_after_the_wait() {
        val control = AnalysisControl()
        val events = mutableListOf<String>()
        control.pause()
        val worker = thread {
            control.boundary(onPaused = { events.add("release") }, onResumed = { events.add("reload") })
        }
        Thread.sleep(50)
        assertEquals(emptyList<String>(), events.toList().filter { it == "reload" })
        control.resume()
        worker.join(2000)
        assertEquals(listOf("release", "reload"), events)
    }

    @Test
    fun photo_in_progress_is_not_frozen_by_a_pause() {
        val control = AnalysisControl()
        control.pause()
        // A step inside a photo returns at once: the pause waits for the next boundary.
        val done = CountDownLatch(1)
        thread {
            control.checkpointStep()
            done.countDown()
        }
        assertTrue(done.await(2, TimeUnit.SECONDS))
    }

    @Test
    fun stop_during_pause_leaves_without_reloading() {
        val control = AnalysisControl()
        control.pause()
        var reloaded = false
        var leftWith: Throwable? = null
        val worker = thread {
            try {
                control.boundary(onResumed = { reloaded = true })
            } catch (e: AnalysisStopped) {
                leftWith = e
            }
        }
        Thread.sleep(50)
        control.stop()
        worker.join(2000)
        assertTrue(leftWith is AnalysisStopped)
        assertFalse(reloaded)
    }

    @Test
    fun stop_while_running_throws_at_the_boundary_and_at_the_step() {
        val control = AnalysisControl()
        control.stop()
        assertThrows(AnalysisStopped::class.java) { control.boundary() }
        assertThrows(AnalysisStopped::class.java) { control.checkpointStep() }
    }

    @Test
    fun pause_then_resume_then_pause_again_releases_again() {
        val control = AnalysisControl()
        var releases = 0
        control.pause()
        thread { control.boundary(onPaused = { releases++ }) }.also { Thread.sleep(30) }
        control.resume()
        Thread.sleep(30)
        control.pause()
        val worker = thread { control.boundary(onPaused = { releases++ }) }
        Thread.sleep(30)
        control.resume()
        worker.join(2000)
        assertEquals(2, releases)
    }
}
