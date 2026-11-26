package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import java.io.InputStream
import java.io.StringReader

class KnownSummaryTest {
    private class Photo(override val id: String, override val name: String, override val length: Long, override val lastModified: Long) : FileNode {
        override fun open(): InputStream? = null
    }

    private val library = """
        [
          {"source":"/s/DCIM/a.jpg","name":"a.jpg","size":100,"modified":500,"mime":"image/jpeg","folder":"DCIM","analysis_version":2},
          {"source":"/s/Pictures/b.png","name":"b \"quoted {brace}\".png","size":200,"modified":600,"mime":"image/png","folder":"Pictures","analysis_version":2},
          {"source":"/s/old/c.jpg","name":"c.jpg","size":300,"modified":700,"mime":"image/jpeg","folder":"old","analysis_version":1},
          {"name":"no-source.jpg","size":400,"modified":800,"mime":"image/jpeg","analysis_version":2}
        ]
    """.trimIndent()

    private val known = KnownSummary.fromItems(2) { action ->
        val array = JSONArray(library)
        for (i in 0 until array.length()) action(array.getJSONObject(i))
    }

    @Test
    fun an_unchanged_photo_is_known_and_its_type_is_reused() {
        val photo = Photo("/s/DCIM/a.jpg", "a.jpg", 100, 500)
        assertFalse(known.toAnalyse(photo))
        assertEquals("image/jpeg", known.reusableMime(photo))
    }

    @Test
    fun a_changed_photo_must_be_analysed_again_and_its_type_is_not_reused() {
        val changed = Photo("/s/DCIM/a.jpg", "a.jpg", 101, 500)
        assertTrue(known.toAnalyse(changed))
        assertNull(known.reusableMime(changed))
    }

    @Test
    fun a_photo_moved_to_another_address_is_recognised_by_name_size_and_date() {
        val moved = Photo("/other/place/a.jpg", "a.jpg", 100, 500)
        assertFalse(known.toAnalyse(moved))
    }

    @Test
    fun an_entry_of_another_analysis_version_or_without_source_is_ignored() {
        assertTrue(known.toAnalyse(Photo("/s/old/c.jpg", "c.jpg", 300, 700)))
        assertTrue(known.toAnalyse(Photo("no-source.jpg", "no-source.jpg", 400, 800)))
    }

    @Test
    fun a_brace_or_a_quote_inside_a_name_does_not_break_the_reading() {
        assertFalse(known.toAnalyse(Photo("/s/Pictures/b.png", "b \"quoted {brace}\".png", 200, 600)))
    }

    @Test
    fun an_empty_or_missing_library_knows_nothing() {
        assertTrue(KnownSummary.EMPTY.toAnalyse(Photo("/s/DCIM/a.jpg", "a.jpg", 100, 500)))
        assertTrue(KnownSummary.fromItems(2) { }.toAnalyse(Photo("/s/DCIM/a.jpg", "a.jpg", 100, 500)))
    }

    @Test
    fun entries_given_one_by_one_answer_as_the_library_text() {
        val entries = JSONArray(library)
        val fromEntries = KnownSummary.fromItems(2) { action ->
            for (i in 0 until entries.length()) action(entries.getJSONObject(i))
        }
        val photos = listOf(
            Photo("/s/DCIM/a.jpg", "a.jpg", 100, 500),
            Photo("/s/DCIM/a.jpg", "a.jpg", 101, 500),
            Photo("/s/Pictures/b.png", "b \"quoted {brace}\".png", 200, 600),
            Photo("/s/old/c.jpg", "c.jpg", 300, 700),
            Photo("/new/d.jpg", "no-source.jpg", 400, 800),
        )
        for (photo in photos) {
            assertEquals(known.toAnalyse(photo), fromEntries.toAnalyse(photo))
            assertEquals(known.reusableMime(photo), fromEntries.reusableMime(photo))
        }
    }
}
