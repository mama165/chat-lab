package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** A library as large as the real one (about 2 800 photos, 768 values each), parsed in one go. */
class LibraryScaleTest {
    @Test
    fun a_large_library_is_parsed_with_all_its_entries_and_vectors() {
        val array = JSONArray()
        for (i in 0 until 2_000) {
            val vector = JSONArray()
            vector.put(JSONArray().apply { repeat(768) { put(((i + it) % 100) / 100.0) } })
            array.put(
                JSONObject()
                    .put("id", "photo-$i")
                    .put("name", "IMG_$i.jpg")
                    .put("path", "/storage/emulated/0/DCIM/IMG_$i.jpg")
                    .put("source", "/storage/emulated/0/DCIM/IMG_$i.jpg")
                    .put("text", "lorem ipsum $i")
                    .put("vectors", vector),
            )
        }
        val entries = Library.parse(array.toString())
        assertEquals(2_000, entries.size)
        assertEquals(768, entries.first().vector?.size)
        assertEquals("photo-1999", entries.last().id)
    }
}
