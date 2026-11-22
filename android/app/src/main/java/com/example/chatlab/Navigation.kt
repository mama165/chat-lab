package com.example.chatlab

/** Les pages à part, qui couvrent tout l'écran et se quittent par la flèche ou le retour. */
enum class Page { TABS, FILTERS, DATES, SETTINGS, REMOVED, WIPE, TESTS, STORAGE }

/** Page à laquelle ramène le retour depuis une page donnée. */
fun Page.parent(): Page = when (this) {
    Page.REMOVED, Page.WIPE, Page.TESTS -> Page.SETTINGS
    Page.DATES -> Page.FILTERS
    Page.STORAGE -> Page.TABS
    else -> Page.TABS
}
