package com.example.chatlab

import com.example.chatlab.TestData.entry
import org.junit.Assert.assertEquals
import org.junit.Test

class LiveLibraryTest {
    @Test
    fun aPublishedBatchIsAddedToWhatIsAlreadyThere() {
        LiveLibrary.replaceAll(listOf(entry("a"), entry("b")))
        LiveLibrary.publish(listOf(entry("c")))
        assertEquals(listOf("a", "b", "c"), LiveLibrary.snapshot().map { it.id })
    }

    @Test
    fun aPhotoAnalysedAgainIsReplacedNotDuplicated() {
        LiveLibrary.replaceAll(listOf(entry("a", "old text")))
        LiveLibrary.publish(listOf(entry("a", "new text")))
        val found = LiveLibrary.snapshot()
        assertEquals(1, found.size)
        assertEquals("new text", found.single().text)
    }

    @Test
    fun replaceAllForgetsTheEarlierPhotos() {
        LiveLibrary.publish(listOf(entry("x")))
        LiveLibrary.replaceAll(listOf(entry("y")))
        assertEquals(listOf("y"), LiveLibrary.snapshot().map { it.id })
    }

    @Test
    fun everyChangeMovesTheVersion() {
        val before = LiveLibrary.changes.value
        LiveLibrary.publish(listOf(entry("z")))
        LiveLibrary.replaceAll(emptyList())
        assertEquals(before + 2, LiveLibrary.changes.value)
    }
}
