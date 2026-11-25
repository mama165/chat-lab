package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipboardLimitTest {
    @Test
    fun a_copy_within_the_limit_goes_to_the_clipboard() {
        val text = "a".repeat(ClipboardLimit.MAX_BYTES)
        assertEquals(ClipboardPlan.Copy(text), ClipboardLimit.plan(text, "run-20261125-021748.jsonl"))
    }

    @Test
    fun a_copy_over_the_limit_points_to_the_journal_file() {
        val text = "a".repeat(ClipboardLimit.MAX_BYTES + 1)
        assertEquals(ClipboardPlan.TooLarge("run-20261125-021748.jsonl"), ClipboardLimit.plan(text, "run-20261125-021748.jsonl"))
    }

    @Test
    fun the_size_is_counted_in_bytes_not_characters() {
        // Each accented letter takes two bytes: 300 000 letters are over 500 000 bytes.
        val text = "é".repeat(300_000)
        assertEquals(ClipboardPlan.TooLarge(null), ClipboardLimit.plan(text, null))
    }

    @Test
    fun the_exact_limit_in_bytes_still_goes_to_the_clipboard() {
        // 250 000 accented letters are exactly 500 000 bytes: the limit itself is allowed.
        val text = "é".repeat(ClipboardLimit.MAX_BYTES / 2)
        assertEquals(ClipboardPlan.Copy(text), ClipboardLimit.plan(text, "run.jsonl"))
    }

    @Test
    fun an_empty_text_goes_to_the_clipboard() {
        assertEquals(ClipboardPlan.Copy(""), ClipboardLimit.plan("", null))
    }
}
