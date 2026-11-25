package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class SuggestionOffsetsTest {
    // Typed "s" (one letter), shown with a grey suggestion after it: the cursor may sit past the typed text.
    private val offsets = SuggestionOffsets(typed = 1)

    @Test
    fun a_position_past_the_typed_text_stays_at_its_end() {
        assertEquals(1, offsets.transformedToOriginal(2))
        assertEquals(1, offsets.transformedToOriginal(9))
        assertEquals(1, offsets.originalToTransformed(4))
    }

    @Test
    fun positions_inside_the_typed_text_are_kept() {
        assertEquals(0, offsets.transformedToOriginal(0))
        assertEquals(1, offsets.transformedToOriginal(1))
        assertEquals(0, offsets.originalToTransformed(0))
        assertEquals(1, offsets.originalToTransformed(1))
    }

    @Test
    fun a_negative_position_is_brought_back_to_the_start() {
        assertEquals(0, offsets.transformedToOriginal(-3))
    }
}
