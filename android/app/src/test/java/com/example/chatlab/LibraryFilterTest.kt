package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.StringReader

/** The filters read from the JSON as a stream must keep the same photos, in the same order, as Filters.matches. */
class LibraryFilterTest {
    private val time = When(now = 1_790_000_000_000L)

    private fun entry(id: String, indoor: Boolean?, faces: Int, text: String, folder: String, mime: String, taken: String?) =
        JSONObject()
            .put("id", id)
            .put("source", "content://media/$id")
            .put("path", "content://media/$id")
            .put("name", "photo_$id.jpg")
            .put("faces", faces)
            .put("text", text)
            .put("folder", folder)
            .put("mime", mime)
            .put("portrait", faces == 1)
            .apply {
                if (indoor != null) put("indoor", indoor)
                if (taken != null) put("taken", taken)
            }
            .put("vectors", JSONArray().put(JSONArray(List(768) { 0.1 })))

    private val json = JSONArray(listOf(
        entry("1", true, 0, "Facture", "Camera", "image/jpeg", "2026-11-03 18:42"),
        entry("2", false, 1, "", "Movies", "image/png", null),
        entry("3", null, 2, "   ", "Camera", "application/pdf", "2026-10-01 09:00"),
        entry("4", true, 0, "Ticket", "Download", "image/jpeg", "2026-11-20 12:00"),
    )).toString()

    private fun expected(filters: Filters): List<String> =
        Library.parse(json).filter { filters.matches(it, time) }.map { it.source }

    private fun actual(filters: Filters): List<String> =
        LibraryCheck.filterMatches(StringReader(json), filters, time)

    @Test
    fun thePlaceMatches() {
        assertEquals(expected(Filters(place = Place.INDOOR)), actual(Filters(place = Place.INDOOR)))
        assertEquals(expected(Filters(place = Place.OUTDOOR)), actual(Filters(place = Place.OUTDOOR)))
    }

    @Test
    fun thePeopleAndTheCloseUpMatch() {
        assertEquals(expected(Filters(people = People.ONE)), actual(Filters(people = People.ONE)))
        assertEquals(expected(Filters(closeUp = true)), actual(Filters(closeUp = true)))
    }

    @Test
    fun theTextMatches() {
        assertEquals(expected(Filters(text = TextFilter.WITH)), actual(Filters(text = TextFilter.WITH)))
        assertEquals(expected(Filters(text = TextFilter.WITHOUT)), actual(Filters(text = TextFilter.WITHOUT)))
    }

    @Test
    fun theFoldersTypesAndKindMatch() {
        val f = Filters(folders = setOf("Camera"), types = setOf("image/jpeg"), kind = FileKind.IMAGE)
        assertEquals(expected(f), actual(f))
        assertEquals(expected(Filters(kind = FileKind.PDF)), actual(Filters(kind = FileKind.PDF)))
    }

    @Test
    fun aDatePresetMatches() {
        val f = Filters(date = DateFilter(preset = DatePreset.LAST_24H))
        assertEquals(expected(f), actual(f))
    }
}
