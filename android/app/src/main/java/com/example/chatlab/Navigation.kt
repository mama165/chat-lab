package com.example.chatlab

/** The separate pages, which cover the whole screen and are left with the arrow or the back button. */
enum class Page { TABS, SETTINGS, STORAGE, ADD_FOLDER, LEVEL, HISTORY, RUN }

/** The page the back button returns to from a given page. */
fun Page.parent(): Page = when (this) {
    Page.STORAGE -> Page.TABS
    Page.LEVEL -> Page.SETTINGS
    // The history is opened from the Photos tab.
    Page.HISTORY -> Page.TABS
    Page.RUN -> Page.HISTORY
    else -> Page.TABS
}
