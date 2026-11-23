package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnalyzerSlotTest {
    private class FakeModels(val id: Int) : AutoCloseable {
        var closed = false
        override fun close() {
            closed = true
        }
    }

    @Test
    fun models_load_on_first_use_only() {
        var loads = 0
        val slot = AnalyzerSlot({ FakeModels(++loads) }, clock = { 0L })
        assertFalse(slot.loaded)
        val first = slot.get()
        assertTrue(slot.loaded)
        assertTrue(first === slot.get())
        assertEquals(1, loads)
    }

    @Test
    fun release_closes_the_models_and_the_next_use_loads_them_again() {
        var loads = 0
        val slot = AnalyzerSlot({ FakeModels(++loads) }, clock = { 0L })
        val first = slot.get()
        slot.release()
        assertTrue(first.closed)
        assertFalse(slot.loaded)
        val second = slot.get()
        assertEquals(2, second.id)
        assertFalse(second.closed)
    }

    @Test
    fun release_before_any_load_does_nothing() {
        val slot = AnalyzerSlot({ FakeModels(1) }, clock = { 0L })
        slot.release()
        assertFalse(slot.loaded)
    }

    @Test
    fun loads_are_recorded_with_reload_flag_and_duration() {
        var now = 1_000L
        val loads = mutableListOf<ModelLoad>()
        var count = 0
        val slot = AnalyzerSlot(
            load = {
                count++
                now += 800L // loading takes 0.8 s
                FakeModels(count)
            },
            clock = { now },
            onLoad = { loads.add(it) },
        )
        slot.get()
        slot.release()
        now += 5_000L
        slot.get()
        assertEquals(
            listOf(
                ModelLoad(reload = false, atMs = 1_000L, millis = 800L),
                ModelLoad(reload = true, atMs = 6_800L, millis = 800L),
            ),
            loads,
        )
    }
}
