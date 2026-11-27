package com.example.chatlab.ui.firstlaunch

import org.junit.Assert.assertEquals
import org.junit.Test

class RecognitionTimelineTest {

    private val eps = 1e-4f

    @Test
    fun track_interpolates_linearly_and_holds_the_ends() {
        val keys = listOf(Key(1f, 0f), Key(3f, 10f))
        assertEquals(0f, track(0f, keys), eps)      // before the first key
        assertEquals(5f, track(2f, keys), eps)      // halfway
        assertEquals(10f, track(9f, keys), eps)     // after the last key
    }

    @Test
    fun track_with_no_key_is_zero() {
        assertEquals(0f, track(1f, emptyList()), eps)
    }

    @Test
    fun photos_land_every_0_35_seconds_starting_at_0_9() {
        assertEquals(0.9f, RecognitionTimeline.landingStart(0), eps)
        assertEquals(1.25f, RecognitionTimeline.landingStart(1), eps)
        assertEquals(1.95f, RecognitionTimeline.landingStart(3), eps)
    }

    @Test
    fun a_photo_is_hidden_until_it_starts_to_fall_then_visible() {
        assertEquals(0f, RecognitionTimeline.photoAlpha(0.5f, 0), eps)
        assertEquals(1f, RecognitionTimeline.photoAlpha(1.0f, 0), eps)
    }

    @Test
    fun a_photo_falls_from_minus_70_to_zero_in_half_a_second() {
        assertEquals(-70f, RecognitionTimeline.photoOffsetDp(0.9f, 0), eps)
        assertEquals(-35f, RecognitionTimeline.photoOffsetDp(1.15f, 0), eps)
        assertEquals(0f, RecognitionTimeline.photoOffsetDp(1.4f, 0), eps)
        assertEquals(0f, RecognitionTimeline.photoOffsetDp(5f, 0), eps)
    }

    @Test
    fun the_first_photo_shimmers_for_two_seconds_then_the_second_one() {
        assertEquals(1.4f, RecognitionTimeline.firstShimmerStart, eps)
        assertEquals(3.4f, RecognitionTimeline.secondShimmerStart, eps)
        assertEquals(5.6f, RecognitionTimeline.checkStart, eps)
    }

    @Test
    fun shimmer_alternates_between_bright_and_dim_every_0_2_seconds() {
        val start = RecognitionTimeline.firstShimmerStart
        assertEquals(1f, RecognitionTimeline.shimmerBrightness(start - 0.1f, start), eps)
        assertEquals(1.6f, RecognitionTimeline.shimmerBrightness(start, start), eps)
        assertEquals(0.65f, RecognitionTimeline.shimmerBrightness(start + 0.2f, start), eps)
        assertEquals(1.6f, RecognitionTimeline.shimmerBrightness(start + 0.4f, start), eps)
        assertEquals(1f, RecognitionTimeline.shimmerBrightness(start + 2.0f, start), eps)
    }

    @Test
    fun the_check_appears_after_both_photos_have_shimmered() {
        assertEquals(0f, RecognitionTimeline.checkAlpha(RecognitionTimeline.checkStart - 0.01f), eps)
        assertEquals(1f, RecognitionTimeline.checkAlpha(RecognitionTimeline.checkStart + 0.2f), eps)
    }

    @Test
    fun the_dots_light_up_in_turn() {
        assertEquals(0.2f, RecognitionTimeline.dotAlpha(0.1f, 1), eps)   // still waiting its delay
        assertEquals(1f, RecognitionTimeline.dotAlpha(0.297f, 0), eps)   // first dot at its peak
        assertEquals(1f, RecognitionTimeline.dotAlpha(0.597f, 1), eps)   // second dot, 0.3 s later
        assertEquals(0.2f, RecognitionTimeline.dotAlpha(0.9f, 0), eps)   // back to dim after one cycle
    }

    @Test
    fun the_whole_step_fades_in_over_0_4_seconds() {
        assertEquals(0f, RecognitionTimeline.blockAlpha(0f), eps)
        assertEquals(1f, RecognitionTimeline.blockAlpha(0.4f), eps)
    }
}
