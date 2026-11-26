package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class PhotoRecordMapperTest {
    private val vector = FloatArray(768) { 0.5f }

    private fun result(
        name: String = "photo_0001.jpg",
        source: String = "content://media/external/images/media/1042",
        vector: FloatArray? = this.vector,
        error: String? = null,
        text: String = "",
        taken: String = "",
        takenMs: Long = 0L,
        modified: Long = 0L,
    ) = ImageResult(
        file = File(name),
        indoor = null,
        faces = 0,
        portrait = false,
        vector = vector,
        millis = 10L,
        error = error,
        taken = taken,
        source = source,
        modified = modified,
        size = 2048L,
        folder = "Camera",
        mime = "image/jpeg",
        takenMs = takenMs,
    )

    @Test
    fun aPhotoWithAnAddressAndAVectorIsStored() {
        val mapped = PhotoRecordMapper.map(listOf(result()), now = 99L)
        assertEquals(1, mapped.records.size)
        assertEquals(0, mapped.withoutAddress)
        val r = mapped.records.single()
        assertEquals("content://media/external/images/media/1042", r.address)
        assertEquals("content://media/external/images/media/1042", r.path)
        assertEquals("photo_0001.jpg", r.name)
        assertEquals("Camera", r.folder)
        assertEquals("image/jpeg", r.mime)
        assertEquals(2048L, r.size)
        assertEquals(ANALYSIS_VERSION, r.analysisVersion)
        assertEquals(vector.size, r.vector.size)
    }

    @Test
    fun aFailedPhotoOrOneWithoutVectorIsNotStoredAndNotCounted() {
        val mapped = PhotoRecordMapper.map(
            listOf(result(name = "a.jpg", error = "illisible"), result(name = "b.jpg", vector = null)),
            now = 1L,
        )
        assertEquals(0, mapped.records.size)
        assertEquals(0, mapped.withoutAddress)
    }

    @Test
    fun aPhotoWithoutAddressIsLeftOutAndCounted() {
        val mapped = PhotoRecordMapper.map(listOf(result(source = ""), result(source = "")), now = 1L)
        assertEquals(0, mapped.records.size)
        assertEquals(2, mapped.withoutAddress)
    }

    @Test
    fun blankOrZeroValuesBecomeNulls() {
        val r = PhotoRecordMapper.map(listOf(result(taken = "", takenMs = 0L, modified = 0L)), now = 7L)
            .records.single()
        assertNull(r.text)
        assertNull(r.taken)
        assertNull(r.takenMs)
        assertNull(r.modified)
    }

    @Test
    fun theDateOfAdditionIsTheModificationDateWhenKnown() {
        val known = PhotoRecordMapper.map(listOf(result(modified = 500L)), now = 7L).records.single()
        assertEquals(500L, known.addedAt)
        val unknown = PhotoRecordMapper.map(listOf(result(modified = 0L)), now = 7L).records.single()
        assertEquals(7L, unknown.addedAt)
    }

    @Test
    fun datesAreKeptWhenPresent() {
        val r = PhotoRecordMapper.map(
            listOf(result(taken = "2026-11-03 18:42", takenMs = 1234L, modified = 42L)),
            now = 1L,
        ).records.single()
        assertEquals("2026-11-03 18:42", r.taken)
        assertEquals(1234L, r.takenMs)
        assertEquals(42L, r.modified)
    }
}
