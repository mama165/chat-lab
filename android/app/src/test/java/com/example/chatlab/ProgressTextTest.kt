package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressTextTest {
    private val running = Progress(running = true, done = 50, total = 200, startedAt = 0L, current = "photo.jpg")

    @Test
    fun fraction() {
        assertEquals(0.25f, running.fraction, 0f)
        assertEquals(0f, Progress().fraction, 0f)
    }

    @Test
    fun remainingFollowsThePace() {
        // 50 photos en 100 s : il en reste 150, soit 300 s.
        assertEquals(300_000L, running.remaining(100_000L))
        assertNull(running.copy(done = 0).remaining(100_000L))
    }

    @Test
    fun pausesAreNotCountedAsWork() {
        val paused = running.pausing(now = 60_000L)
        assertTrue(paused.paused)
        // Frozen at 60 s, whatever the moment we look.
        assertEquals(60_000L, paused.activeElapsed(500_000L))
        val resumed = paused.resuming(now = 160_000L)
        assertFalse(resumed.paused)
        assertEquals(100_000L, resumed.pausedTotal)
        assertEquals(80_000L, resumed.activeElapsed(180_000L))
    }

    @Test
    fun pausingTwiceOrResumingWhileRunningChangesNothing() {
        val paused = running.pausing(10L)
        assertEquals(paused, paused.pausing(99L))
        assertEquals(running, running.resuming(99L))
    }

    @Test
    fun lineShowsCountPercentAndTimes() {
        val line = progressLine(running, 100_000L)
        assertTrue(line, line.startsWith("50 / 200 fichiers (25 %)"))
        assertTrue(line, line.contains("écoulé 1:40"))
        assertTrue(line, line.contains("reste ~5:00"))
        assertTrue(line, line.endsWith("\nphoto.jpg"))
    }

    @Test
    fun lineWhilePaused() {
        val line = progressLine(running.pausing(100_000L), 120_000L)
        assertTrue(line, line.contains("en pause"))
        assertFalse(line, line.contains("photo.jpg"))
    }

    @Test
    fun lineMentionsErrors() {
        assertTrue(progressLine(running.copy(errors = 2), 1000L).contains("2 erreur(s)"))
    }

    @Test
    fun lineBeforeCountingAndWhenStopped() {
        assertEquals("Recherche des images…", progressLine(Progress(running = true), 0L))
        assertEquals("Préparation…", progressLine(Progress(running = true, message = "Préparation…"), 0L))
        assertEquals("Terminé", progressLine(Progress(running = false, message = "Terminé"), 0L))
    }

    @Test
    fun clockFormatsMinutesAndSeconds() {
        assertEquals("0:00", clock(0L))
        assertEquals("1:05", clock(65_000L))
        assertEquals("75:00", clock(4_500_000L))
        assertEquals("0:00", clock(-5L))
    }

    @Test
    fun remainingText() {
        assertEquals("Estimation du temps restant…", remainingText(null))
        assertEquals("Moins d'une minute restante", remainingText(20_000L))
        assertEquals("Environ 1 minute restante", remainingText(70_000L))
        assertEquals("Environ 2 minutes restantes", remainingText(110_000L))
        assertEquals("Environ 50 minutes restantes", remainingText(50 * 60_000L))
        assertEquals("Environ 10 minutes restantes", remainingText(10 * 60_000L))
        assertEquals("Environ 1 heure et 52 minutes restantes", remainingText(112 * 60_000L))
        assertEquals("Environ 2 heures et 13 minutes restantes", remainingText(133 * 60_000L))
        assertEquals("Environ 2 heures et 40 minutes restantes", remainingText(160 * 60_000L))
        assertEquals("Environ 2 heures et 50 minutes restantes", remainingText(170 * 60_000L))
        assertEquals("Environ 2 heures restantes", remainingText(120 * 60_000L))
        assertEquals("Environ 1 heure et 1 minute restantes", remainingText(61 * 60_000L))
    }

    private val folders = listOf(FolderProgress("Camera", 0, 0), FolderProgress("Screenshots", 0, 14))

    @Test
    fun aFolderWithNothingToDoIsMarkedDone() {
        assertTrue(FolderProgress("Camera", 0, 0).nothingNew)
        assertTrue(FolderProgress("Camera", 0, 0).finished)
        assertFalse(FolderProgress("Screenshots", 6, 14).finished)
        assertTrue(FolderProgress("Screenshots", 14, 14).finished)
    }

    @Test
    fun advancingMovesTheRightFolderAndTheTotal() {
        val start = running.copy(done = 0, total = 14, folders = folders)
        val next = start.advancing(1, "qr.png")
        assertEquals(1, next.done)
        assertEquals("qr.png", next.current)
        assertEquals(1, next.folders[1].done)
        assertEquals(0, next.folders[0].done)
        assertEquals("qr.png", next.folders[1].current)
    }

    @Test
    fun advancingSeveralTimesAddsUp() {
        var progress = running.copy(done = 0, total = 14, folders = folders)
        repeat(6) { progress = progress.advancing(1, "f$it") }
        assertEquals(6, progress.done)
        assertEquals(6, progress.folders[1].done)
        assertEquals(14, progress.folders[1].total)
    }

    @Test
    fun advancingAnUnknownFolderStillCountsTheFile() {
        val next = running.copy(done = 3, folders = folders).advancing(9, "x")
        assertEquals(4, next.done)
        assertEquals(folders, next.folders)
    }
}
