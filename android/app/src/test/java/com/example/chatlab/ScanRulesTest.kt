package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

class ScanRulesTest {
    private val jpegHead = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10)
    private val textHead = "juste du texte".toByteArray()

    @Test
    fun aSupportedImageIsAnalysed() {
        assertEquals(ScanDecision.Analyze(FileSniffer.JPEG), ScanRules.classify(1000, jpegHead))
    }

    @Test
    fun anOtherTypeIsIgnoredWithItsType() {
        val gif = "GIF89a".toByteArray() + ByteArray(8)
        assertEquals(ScanDecision.Ignore(FileSniffer.GIF), ScanRules.classify(1000, gif))
        val pdf = "%PDF-1.4".toByteArray()
        assertEquals(ScanDecision.Ignore(FileSniffer.PDF), ScanRules.classify(1000, pdf))
    }

    @Test
    fun anUnknownTypeIsIgnoredWithoutType() {
        assertEquals(ScanDecision.Ignore(null), ScanRules.classify(1000, textHead))
    }

    @Test
    fun aFileThatCannotBeOpenedIsInaccessible() {
        assertEquals(ScanDecision.Unreadable(FailureReason.INACCESSIBLE), ScanRules.classify(1000, null))
    }

    @Test
    fun anEmptyFileIsReportedEmpty() {
        assertEquals(ScanDecision.Unreadable(FailureReason.EMPTY), ScanRules.classify(0, ByteArray(0)))
        assertEquals(ScanDecision.Unreadable(FailureReason.EMPTY), ScanRules.classify(0, jpegHead))
        assertEquals(ScanDecision.Unreadable(FailureReason.EMPTY), ScanRules.classify(10, ByteArray(0)))
    }

    @Test
    fun inaccessibleWinsOverEmpty() {
        assertEquals(ScanDecision.Unreadable(FailureReason.INACCESSIBLE), ScanRules.classify(0, null))
    }

    @Test
    fun theExtensionNeverMatters() {
        // Le nom n'entre même pas dans la règle : seuls la taille et les octets comptent.
        assertEquals(ScanDecision.Analyze(FileSniffer.JPEG), ScanRules.classify(5, jpegHead))
        assertEquals(ScanDecision.Ignore(null), ScanRules.classify(5, textHead))
    }

    @Test
    fun failureLabelsAreTheOnesOnScreen() {
        assertEquals("fichier inaccessible", FailureReason.INACCESSIBLE.label)
        assertEquals("image illisible", FailureReason.UNREADABLE.label)
        assertEquals("fichier vide", FailureReason.EMPTY.label)
        assertEquals("erreur d'analyse", FailureReason.ANALYSIS.label)
    }

    @Test
    fun errorsAreClassedByTheirCause() {
        assertEquals(FailureReason.INACCESSIBLE, Failures.reasonOf(FileNotFoundException("absent")))
        assertEquals(FailureReason.INACCESSIBLE, Failures.reasonOf(SecurityException("refusé")))
        assertEquals(FailureReason.INACCESSIBLE, Failures.reasonOf(IOException("lecture")))
        assertEquals(FailureReason.UNREADABLE, Failures.reasonOf(OutOfMemoryError()))
        assertEquals(FailureReason.UNREADABLE, Failures.reasonOf(ImageFailure(FailureReason.UNREADABLE)))
        assertEquals(FailureReason.EMPTY, Failures.reasonOf(ImageFailure(FailureReason.EMPTY)))
        assertEquals(FailureReason.ANALYSIS, Failures.reasonOf(IllegalStateException("modèle")))
        assertEquals(FailureReason.ANALYSIS, Failures.reasonOf(RuntimeException()))
    }

    @Test
    fun anImageFailureKeepsItsReasonAndMessage() {
        val plain = ImageFailure(FailureReason.UNREADABLE)
        assertEquals("image illisible", plain.message)
        val detailed = ImageFailure(FailureReason.INACCESSIBLE, "disparu")
        assertEquals("disparu", detailed.message)
        assertTrue(detailed is Exception)
    }
}
