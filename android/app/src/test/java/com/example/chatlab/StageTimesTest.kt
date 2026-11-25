package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class StageTimesTest {
    @Test
    fun the_times_add_up_per_stage() {
        val times = StageTimes()
        times.add(StageTimes.IMAGE, 300)
        times.add(StageTimes.IMAGE, 200)
        times.add(StageTimes.TEXT, 50)
        assertEquals(mapOf(StageTimes.IMAGE to 500L, StageTimes.TEXT to 50L), times.totals())
    }
}
