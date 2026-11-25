package com.example.chatlab

import java.util.TimeZone

/** The date a photo was taken, in milliseconds: the stored value, or else the date written as text. */
object TakenTime {
    /** Null when the photo has no usable date. Same rule as Library.parse. */
    fun millis(explicit: Long, taken: String): Long? {
        if (explicit > 0) return explicit
        if (taken.isBlank()) return null
        return Dates.parseTakenMillis(taken, TimeZone.getDefault())?.takeIf { it > 0 }
    }
}
