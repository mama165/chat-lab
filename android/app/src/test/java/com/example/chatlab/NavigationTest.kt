package com.example.chatlab

import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationTest {
    @Test
    fun pagesOpenedFromSettingsGoBackToSettings() {
        assertEquals(Page.SETTINGS, Page.REMOVED.parent())
        assertEquals(Page.SETTINGS, Page.WIPE.parent())
        assertEquals(Page.SETTINGS, Page.TESTS.parent())
    }

    @Test
    fun settingsAndFiltersGoBackToTheTabs() {
        assertEquals(Page.TABS, Page.SETTINGS.parent())
        assertEquals(Page.TABS, Page.FILTERS.parent())
        assertEquals(Page.TABS, Page.TABS.parent())
    }
}
