package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class RunningScreenTest {
    private val running = Progress(running = true, total = 10)

    @Test
    fun a_running_analysis_shows_the_list() {
        assertEquals(RunningScreen.RUNNING, running.runningScreen())
    }

    @Test
    fun a_paused_analysis_shows_the_pause_view() {
        assertEquals(RunningScreen.PAUSED, running.copy(paused = true).runningScreen())
    }

    @Test
    fun resume_then_reload_then_running_again() {
        val paused = running.copy(paused = true)
        val resumed = paused.resuming(now = 1_000L)
        assertEquals(RunningScreen.RUNNING, resumed.runningScreen())
        val loading = resumed.loadingStarted()
        assertEquals(RunningScreen.RELOADING, loading.runningScreen())
        assertEquals(RunningScreen.RUNNING, loading.loadingDone().runningScreen())
    }

    @Test
    fun the_reloading_view_stays_while_any_worker_still_loads() {
        val two = running.loadingStarted().loadingStarted()
        assertEquals(RunningScreen.RELOADING, two.loadingDone().runningScreen())
        assertEquals(RunningScreen.RUNNING, two.loadingDone().loadingDone().runningScreen())
    }

    @Test
    fun the_count_of_loading_workers_never_goes_below_zero() {
        assertEquals(0, running.loadingDone().loadingDone().loadingModels)
    }
}
