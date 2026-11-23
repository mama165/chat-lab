package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class ReloadRowsTest {
    private val base = Progress(running = true, total = 10)

    @Test
    fun rows_follow_the_loading_order_of_the_models() {
        assertEquals(
            listOf(ModelNames.IMAGES, ModelNames.TEXTS, ModelNames.FACES),
            reloadRows(emptyMap(), loading = true).map { it.first },
        )
    }

    @Test
    fun first_row_turns_then_the_next_ones_wait_while_loading() {
        val rows = reloadRows(emptyMap(), loading = true).map { it.second }
        assertEquals(listOf(ModelRowState.LOADING, ModelRowState.WAITING, ModelRowState.WAITING), rows)
    }

    @Test
    fun loaded_models_are_checked_with_the_turning_one_next() {
        val steps = mapOf(ModelNames.IMAGES to 800L)
        val rows = reloadRows(steps, loading = true).map { it.second }
        assertEquals(listOf(ModelRowState.DONE, ModelRowState.LOADING, ModelRowState.WAITING), rows)
    }

    @Test
    fun all_checked_when_nothing_is_loading_any_more() {
        val steps = mapOf(ModelNames.IMAGES to 800L, ModelNames.TEXTS to 900L, ModelNames.FACES to 400L)
        assertEquals(List(3) { ModelRowState.DONE }, reloadRows(steps, loading = false).map { it.second })
    }

    @Test
    fun each_model_keeps_its_real_time_and_the_first_one_seen() {
        val progress = base.modelDone(ModelNames.IMAGES, 800L).modelDone(ModelNames.IMAGES, 999L)
        assertEquals(800L, progress.modelSteps.getValue(ModelNames.IMAGES))
    }

    @Test
    fun a_new_reload_forgets_the_models_of_the_previous_one() {
        val done = base.modelDone(ModelNames.IMAGES, 800L).loadingStarted()
        assertEquals(emptyMap<String, Long>(), done.modelSteps)
        assertEquals(1, done.loadingModels)
    }
}

class ReloadSummaryTest {
    @Test
    fun all_three_models_reloaded_in_the_total_time() {
        val steps = mapOf(ModelNames.IMAGES to 800L, ModelNames.TEXTS to 900L, ModelNames.FACES to 400L)
        assertEquals("Modèles rechargés : 3 sur 3, en 2,1 s", reloadSummary(steps))
    }

    @Test
    fun a_partial_reload_counts_only_the_models_loaded() {
        assertEquals("Modèles rechargés : 1 sur 3, en 0,8 s", reloadSummary(mapOf(ModelNames.IMAGES to 800L)))
    }
}

class ReloadDoneTest {
    private val paused = Progress(running = true, total = 10, paused = true)

    @Test
    fun the_photos_go_on_only_once_every_worker_has_its_models() {
        val reloading = paused.resuming(now = 0L).loadingStarted().loadingStarted()
        assertEquals(false, reloading.reloadDone)
        assertEquals(false, reloading.loadingDone().reloadDone)
        assertEquals(true, reloading.loadingDone().loadingDone().reloadDone)
    }

    @Test
    fun resuming_clears_the_previous_reload() {
        val finished = paused.loadingStarted().loadingDone()
        assertEquals(true, finished.reloadDone)
        assertEquals(false, finished.copy(paused = true).resuming(now = 0L).reloadDone)
    }
}
