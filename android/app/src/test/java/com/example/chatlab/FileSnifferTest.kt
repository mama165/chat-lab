package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSnifferTest {
    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    private fun ascii(text: String) = text.toByteArray(Charsets.ISO_8859_1)

    @Test
    fun jpegStartsWithFFD8FF() {
        assertEquals(FileSniffer.JPEG, FileSniffer.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0x10)))
        assertEquals(FileSniffer.JPEG, FileSniffer.detect(bytes(0xFF, 0xD8, 0xFF, 0xE1)))
    }

    @Test
    fun pngNeedsTheFullEightByteSignature() {
        val png = bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D)
        assertEquals(FileSniffer.PNG, FileSniffer.detect(png))
        assertNull(FileSniffer.detect(bytes(0x89, 0x50, 0x4E, 0x47)))
    }

    @Test
    fun webpIsRiffThenWebpAtOffsetEight() {
        val webp = ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WEBPVP8 ")
        assertEquals(FileSniffer.WEBP, FileSniffer.detect(webp))
    }

    @Test
    fun otherRiffFilesAreNotWebp() {
        val wav = ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("WAVEfmt ")
        val avi = ascii("RIFF") + bytes(0x24, 0, 0, 0) + ascii("AVI LIST")
        assertNull(FileSniffer.detect(wav))
        assertNull(FileSniffer.detect(avi))
    }

    @Test
    fun gifBothVersions() {
        assertEquals(FileSniffer.GIF, FileSniffer.detect(ascii("GIF89a") + bytes(1, 0, 1, 0)))
        assertEquals(FileSniffer.GIF, FileSniffer.detect(ascii("GIF87a") + bytes(1, 0, 1, 0)))
        assertNull(FileSniffer.detect(ascii("GIF88a")))
    }

    @Test
    fun heicBrands() {
        for (brand in listOf("heic", "heix", "mif1")) {
            val head = bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii(brand) + bytes(0, 0, 0, 0)
            assertEquals(brand, FileSniffer.HEIC, FileSniffer.detect(head))
        }
        // An MP4 also has "ftyp", but with another brand.
        val mp4 = bytes(0, 0, 0, 0x18) + ascii("ftyp") + ascii("isom") + bytes(0, 0, 0, 0)
        assertNull(FileSniffer.detect(mp4))
    }

    @Test
    fun pdfStartsWithPercentPdf() {
        assertEquals(FileSniffer.PDF, FileSniffer.detect(ascii("%PDF-1.7\n%")))
        assertNull(FileSniffer.detect(ascii("%PDF")))
    }

    @Test
    fun unknownAndShortInputsGiveNull() {
        assertNull(FileSniffer.detect(ByteArray(0)))
        assertNull(FileSniffer.detect(ascii("Bonjour, ceci est un texte.")))
        assertNull(FileSniffer.detect(bytes(0xFF, 0xD8)))
        assertNull(FileSniffer.detect(bytes(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0)))
    }

    @Test
    fun onlyJpegPngAndWebpAreSupported() {
        assertTrue(FileSniffer.isSupported(FileSniffer.JPEG))
        assertTrue(FileSniffer.isSupported(FileSniffer.PNG))
        assertTrue(FileSniffer.isSupported(FileSniffer.WEBP))
        assertFalse(FileSniffer.isSupported(FileSniffer.GIF))
        assertFalse(FileSniffer.isSupported(FileSniffer.HEIC))
        assertFalse(FileSniffer.isSupported(FileSniffer.PDF))
        assertFalse(FileSniffer.isSupported(null))
    }

    @Test
    fun labelsAndTheirInverse() {
        assertEquals("JPG", FileSniffer.label(FileSniffer.JPEG))
        assertEquals("PNG", FileSniffer.label(FileSniffer.PNG))
        assertEquals("WebP", FileSniffer.label(FileSniffer.WEBP))
        assertEquals("PDF", FileSniffer.label(FileSniffer.PDF))
        assertEquals("TIFF", FileSniffer.label("image/tiff"))
        for (mime in listOf(FileSniffer.JPEG, FileSniffer.PNG, FileSniffer.WEBP, FileSniffer.GIF, FileSniffer.HEIC, FileSniffer.PDF)) {
            assertEquals(mime, FileSniffer.mimeOf(FileSniffer.label(mime)))
        }
        assertNull(FileSniffer.mimeOf("XYZ"))
    }

    @Test
    fun headSizeMatchesTheGoScanner() {
        assertEquals(64, FileSniffer.HEAD_SIZE)
    }
}
