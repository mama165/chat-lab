package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisBannerTest {
    @Test
    fun runningAnalysisIsShownWhateverTheNotice() {
        assertEquals(BannerKind.RUNNING, analysisBannerKind(running = true, done = 3, total = 10, notice = false))
        assertEquals(BannerKind.RUNNING, analysisBannerKind(running = true, done = 3, total = 10, notice = true))
    }

    @Test
    fun finishedNoticeOnlyAfterARunThatCompleted() {
        assertEquals(BannerKind.FINISHED, analysisBannerKind(running = false, done = 10, total = 10, notice = true))
    }

    @Test
    fun noNoticeMeansNothingShown() {
        assertEquals(BannerKind.HIDDEN, analysisBannerKind(running = false, done = 10, total = 10, notice = false))
        assertEquals(BannerKind.HIDDEN, analysisBannerKind(running = false, done = 0, total = 0, notice = false))
    }

    @Test
    fun noticeWithoutACompletedRunIsNotShown() {
        assertEquals(BannerKind.HIDDEN, analysisBannerKind(running = false, done = 4, total = 10, notice = true))
        assertEquals(BannerKind.HIDDEN, analysisBannerKind(running = false, done = 0, total = 0, notice = true))
    }
}
