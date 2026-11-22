package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ReadProgressTest {
    @Before
    fun setUp() {
        ReadProgress.counts.value.keys.toList().forEach { ReadProgress.finish(it) }
    }

    @Test
    fun aFolderIsListedWhileItIsReadAndForgottenAtTheEnd() {
        ReadProgress.start("dossier")
        assertEquals(0, ReadProgress.counts.value["dossier"])
        ReadProgress.report("dossier", ReadProgress.EVERY)
        assertEquals(ReadProgress.EVERY, ReadProgress.counts.value["dossier"])
        ReadProgress.finish("dossier")
        assertNull(ReadProgress.counts.value["dossier"])
    }

    @Test
    fun onlyEveryFiftiethFileIsReported() {
        ReadProgress.start("dossier")
        ReadProgress.report("dossier", 7)
        assertEquals(0, ReadProgress.counts.value["dossier"])
        ReadProgress.report("dossier", 100)
        assertEquals(100, ReadProgress.counts.value["dossier"])
    }
}
