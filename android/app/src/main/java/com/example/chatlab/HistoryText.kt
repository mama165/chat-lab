package com.example.chatlab

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** The texts of the history list (view 2.6), kept apart so that they can be tested. */
object HistoryText {
    /** A duration as the list shows it: "38 min", "1 h 02". */
    fun shortDuration(totalMinutes: Int): String {
        val minutes = totalMinutes.coerceAtLeast(0)
        return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${(minutes % 60).toString().padStart(2, '0')}"
    }

    /** Photos per second: "2 photos /s" when whole, "3,6 photos /s" otherwise. */
    fun photosPerSecond(value: Double): String {
        val rounded = value.roundToLong()
        return if (abs(value - rounded) < 0.05) "$rounded photos /s"
        else "%.1f photos /s".format(Locale.FRANCE, value)
    }

    /** The heating, from the thermal status of Android (0 = none, 6 = shutdown). */
    fun heating(level: Int): String = when (level) {
        0 -> "normale"
        1 -> "légère"
        2 -> "modérée"
        3 -> "élevée"
        4 -> "critique"
        else -> "d'urgence"
    }

    /** "1 247 dans 9 dossiers", or "1 dossier" for one. */
    fun photosInFolders(photos: Int, folders: Int): String =
        "$photos dans $folders ${if (folders == 1) "dossier" else "dossiers"}"

    /** The photos per second of a run, from its photos and its duration in seconds. */
    fun speed(photos: Int, seconds: Double): Double = if (seconds <= 0.0) 0.0 else photos / seconds
}
