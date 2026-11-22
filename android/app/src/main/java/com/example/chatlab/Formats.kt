package com.example.chatlab

import java.util.Locale

object Formats {
    /** « 4 Mo », « 820 Ko », « 1,2 Go » : tailles lisibles, à la française. */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes o"
        val kilo = bytes / 1024.0
        if (kilo < 1024) return "${kilo.toInt()} Ko"
        val mega = kilo / 1024.0
        if (mega < 1024) return if (mega < 10) one(mega) + " Mo" else "${mega.toInt()} Mo"
        return one(mega / 1024.0) + " Go"
    }

    private fun one(value: Double): String {
        val text = String.format(Locale.FRANCE, "%.1f", value)
        return text.removeSuffix(",0")
    }

    /** « 1 photo », « 3 photos », « aucune photo ». */
    fun photos(count: Int): String = when (count) {
        0 -> "aucune photo"
        1 -> "1 photo"
        else -> "$count photos"
    }

    /** « 1 résultat », « 4 résultats ». */
    fun results(count: Int): String = if (count <= 1) "$count résultat" else "$count résultats"
}
