package com.example.chatlab

import android.app.ApplicationExitInfo
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticsTest {
    @Test
    fun exitReasonsHaveReadableNames() {
        assertEquals("LOW_MEMORY", Diagnostics.reasonName(ApplicationExitInfo.REASON_LOW_MEMORY))
        assertEquals("CRASH_NATIVE", Diagnostics.reasonName(ApplicationExitInfo.REASON_CRASH_NATIVE))
        assertEquals("ANR", Diagnostics.reasonName(ApplicationExitInfo.REASON_ANR))
    }

    @Test
    fun unknownCodeIsKeptAsItIs() {
        assertEquals("CODE_999", Diagnostics.reasonName(999))
    }
}
