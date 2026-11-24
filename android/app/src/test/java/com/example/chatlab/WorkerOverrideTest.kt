package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorkerOverrideTest {
    @Test
    fun the_automatic_choice_forces_nothing() {
        assertNull(WorkerOverride.parse(null))
        assertNull(WorkerOverride.parse("auto"))
    }

    @Test
    fun a_forced_number_between_one_and_four_is_kept() {
        assertEquals(1, WorkerOverride.parse("1"))
        assertEquals(2, WorkerOverride.parse("2"))
        assertEquals(4, WorkerOverride.parse("4"))
    }

    @Test
    fun a_number_out_of_range_or_not_a_number_is_ignored() {
        assertNull(WorkerOverride.parse("0"))
        assertNull(WorkerOverride.parse("5"))
        assertNull(WorkerOverride.parse("deux"))
    }
}
