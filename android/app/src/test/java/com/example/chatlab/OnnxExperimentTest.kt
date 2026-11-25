package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnnxExperimentTest {
    @Test
    fun the_arena_is_on_unless_it_was_switched_off() {
        // Nothing saved yet: the arena stays on, as before the experiment.
        assertTrue(OnnxExperiment.arenaFromRaw(null))
        assertTrue(OnnxExperiment.arenaFromRaw("on"))
        assertFalse(OnnxExperiment.arenaFromRaw("off"))
    }

    @Test
    fun the_threads_keep_only_the_choices_of_the_page() {
        assertEquals(0, OnnxExperiment.threadsFromRaw(null))
        assertEquals(0, OnnxExperiment.threadsFromRaw("0"))
        assertEquals(2, OnnxExperiment.threadsFromRaw("2"))
        assertEquals(8, OnnxExperiment.threadsFromRaw("8"))
        assertEquals(0, OnnxExperiment.threadsFromRaw("3"))
        assertEquals(0, OnnxExperiment.threadsFromRaw("beaucoup"))
    }

    @Test
    fun an_unknown_saved_value_keeps_the_default() {
        assertTrue(OnnxExperiment.arenaFromRaw("peut-être"))
        assertEquals(true, OnnxExperiment.arenaFromRaw(""))
    }
}
