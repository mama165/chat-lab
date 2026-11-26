package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class AnalysisSavesTest {
    @Test
    fun eachSaveDuringAnAnalysisIsCounted() {
        val before = AnalysisProgress.saves.value
        AnalysisProgress.saved()
        AnalysisProgress.saved()
        assertEquals(before + 2, AnalysisProgress.saves.value)
    }
}
