package com.example.chatlab

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileKindAndDateTest {
    private val utc = TimeZone.getTimeZone("UTC")

    @Test
    fun imageGroupsEveryPictureFormatButNotDocuments() {
        for (mime in listOf(FileSniffer.JPEG, FileSniffer.PNG, FileSniffer.WEBP, FileSniffer.GIF, FileSniffer.HEIC)) {
            assertTrue(FileKind.IMAGE.matches(mime), mime)
        }
        assertFalse(FileKind.IMAGE.matches(FileSniffer.PDF))
        assertFalse(FileKind.IMAGE.matches(null))
        assertFalse(FileKind.IMAGE.matches(""))
    }

    @Test
    fun pdfMatchesOnlyPdf() {
        assertTrue(FileKind.PDF.matches(FileSniffer.PDF))
        assertFalse(FileKind.PDF.matches(FileSniffer.JPEG))
        assertFalse(FileKind.PDF.matches(null))
    }

    @Test
    fun anyKindMatchesEverythingIncludingUnknownFiles() {
        assertTrue(FileKind.ANY.matches(null))
        assertTrue(FileKind.ANY.matches(FileSniffer.PDF))
        assertTrue(FileKind.ANY.matches(FileSniffer.JPEG))
    }

    @Test
    fun typeChipAndKindFilterInTheSearch() {
        assertEquals("Type · Image", Filters(kind = FileKind.IMAGE).chips().single().label)
        assertEquals("Type · PDF", Filters(kind = FileKind.PDF).chips().single().label)
        assertTrue(Filters(kind = FileKind.ANY).isDefault)
    }

    @Test
    fun todayStartsAtMidnightOfTheSameDay() {
        val now = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 23, 15, 42, 7) }.timeInMillis
        val (start, end) = DateFilter.window(DatePreset.TODAY, When(now, utc))
        val midnight = Calendar.getInstance(utc).apply { clear(); set(2026, Calendar.NOVEMBER, 23, 0, 0, 0) }.timeInMillis
        assertEquals(midnight, start)
        assertEquals(now + 60_000L, end)
    }

    @Test
    fun todayChipLabelAndPresetOrder() {
        assertEquals("Date · Aujourd'hui", Filters(date = DateFilter(DatePreset.TODAY)).chips(When(0L, utc)).single().label)
        assertEquals(DatePreset.ALL, DatePreset.entries.first())
        assertEquals(DatePreset.TODAY, DatePreset.entries[1])
    }
}
