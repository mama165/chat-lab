package com.example.chatlab

/** The separate pages, which cover the whole screen and are left with the arrow or the back button. */
enum class Page { TABS, FILTERS, DATES, SETTINGS, REMOVED, WIPE, TESTS, STORAGE }

/** The page the back button returns to from a given page. */
fun Page.parent(): Page = when (this) {
    Page.REMOVED, Page.WIPE, Page.TESTS -> Page.SETTINGS
    Page.DATES -> Page.FILTERS
    Page.STORAGE -> Page.TABS
    else -> Page.TABS
}
