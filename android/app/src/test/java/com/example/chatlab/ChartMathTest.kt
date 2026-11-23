package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartMathTest {
    @Test
    fun x_position_is_the_share_of_the_run_and_stays_in_the_frame() {
        assertEquals(0.5f, ChartMath.xFraction(150, 300), 1e-6f)
        assertEquals(0f, ChartMath.xFraction(0, 300), 1e-6f)
        assertEquals(1f, ChartMath.xFraction(999, 300), 1e-6f)
        assertEquals(0f, ChartMath.xFraction(10, 0), 1e-6f)
    }

    @Test
    fun y_position_is_clamped_between_zero_and_the_axis_maximum() {
        assertEquals(0.25f, ChartMath.yFraction(25f, 100f), 1e-6f)
        assertEquals(1f, ChartMath.yFraction(250f, 100f), 1e-6f)
        assertEquals(0f, ChartMath.yFraction(-4f, 100f), 1e-6f)
        assertEquals(0f, ChartMath.yFraction(4f, 0f), 1e-6f)
    }

    @Test
    fun graduations_are_every_half_hour_from_the_start() {
        assertEquals(listOf(0, 1800, 3600, 5400), ChartMath.halfHourMarks(5400))
        assertEquals(listOf(0), ChartMath.halfHourMarks(600))
        assertEquals(emptyList<Int>(), ChartMath.halfHourMarks(0))
    }

    @Test
    fun clock_labels_use_the_local_time_of_the_run() {
        // 14:00 in a zone at +0 h, then 30 minutes later
        val start = 14L * 3600_000L
        assertEquals("14:00", ChartMath.clockLabel(start, 0, zoneOffsetMs = 0))
        assertEquals("14:30", ChartMath.clockLabel(start, 1800, zoneOffsetMs = 0))
        assertEquals("00:15", ChartMath.clockLabel(start, 36_900, zoneOffsetMs = 0))
    }

    @Test
    fun memory_axis_is_a_multiple_of_200_and_at_least_200() {
        assertEquals(200, ChartMath.memoryAxisMax(0))
        assertEquals(200, ChartMath.memoryAxisMax(200))
        assertEquals(400, ChartMath.memoryAxisMax(201))
        assertEquals(800, ChartMath.memoryAxisMax(751))
    }
}
