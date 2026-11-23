package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReadProgressTest {
    @Before
    fun setUp() {
        ReadProgress.counts.value.keys.toList().forEach { folder -> ReadProgress.finish(folder, ReadProgress.start(folder)) }
    }

    @Test
    fun aFolderIsListedWhileItIsReadAndForgottenAtTheEnd() {
        val lecture = ReadProgress.start("dossier")
        assertEquals(0, ReadProgress.counts.value["dossier"])
        ReadProgress.report("dossier", lecture, ReadProgress.EVERY)
        assertEquals(ReadProgress.EVERY, ReadProgress.counts.value["dossier"])
        ReadProgress.finish("dossier", lecture)
        assertNull(ReadProgress.counts.value["dossier"])
    }

    @Test
    fun onlyEveryFiftiethFileIsReported() {
        val lecture = ReadProgress.start("dossier")
        ReadProgress.report("dossier", lecture, 7)
        assertEquals(0, ReadProgress.counts.value["dossier"])
        ReadProgress.report("dossier", lecture, 100)
        assertEquals(100, ReadProgress.counts.value["dossier"])
    }

    @Test
    fun anOlderReadingNoLongerMovesTheCounter() {
        val older = ReadProgress.start("dossier")
        val newer = ReadProgress.start("dossier")
        ReadProgress.report("dossier", newer, 50)
        ReadProgress.report("dossier", older, 500)
        assertEquals(50, ReadProgress.counts.value["dossier"])
        // The old reading ends: it does not remove the reading in progress.
        ReadProgress.finish("dossier", older)
        assertEquals(50, ReadProgress.counts.value["dossier"])
        ReadProgress.finish("dossier", newer)
        assertNull(ReadProgress.counts.value["dossier"])
    }

    @Test
    fun theTotalOfAFolderIsKeptWhileItIsRead() {
        val lecture = ReadProgress.start("dossier-total", total = 40)
        assertEquals(40, ReadProgress.totalCounts.value["dossier-total"])
        ReadProgress.finish("dossier-total", lecture)
        assertNull(ReadProgress.totalCounts.value["dossier-total"])
    }
}
