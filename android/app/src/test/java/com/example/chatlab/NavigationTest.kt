package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
    @Test
    fun pagesOpenedFromSettingsGoBackToSettings() {
        assertEquals(Page.SETTINGS, Page.WIPE.parent())
        assertEquals(Page.SETTINGS, Page.TESTS.parent())
    }

    @Test
    fun settingsGoBackToTheTabs() {
        assertEquals(Page.TABS, Page.SETTINGS.parent())
        assertEquals(Page.TABS, Page.TABS.parent())
    }
}

class HistoryNavigationTest {
    @Test
    fun theHistoryGoesBackToThePhotosTabs() {
        assertEquals(Page.TABS, Page.HISTORY.parent())
    }

    @Test
    fun theAnalysisDetailGoesBackToTheHistory() {
        assertEquals(Page.HISTORY, Page.RUN.parent())
    }
}
