package com.example.chatlab

import java.util.Calendar

/** Une date sans heure ni fuseau, assez pour filtrer par mois ou par année. */
data class SimpleDate(val year: Int, val month: Int, val day: Int) {
    companion object {
        /** La date du jour (ou d'un instant donné), dans le fuseau du téléphone. */
        fun of(millis: Long): SimpleDate {
            val calendar = Calendar.getInstance().apply { timeInMillis = millis }
            return SimpleDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }
    }
}

object Dates {
    private val MONTHS = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin",
        "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    )

    /**
     * Lit la date d'une photo telle que l'analyse l'écrit, « jj/mm/aaaa » suivi ou non de l'heure.
     * Renvoie null pour une date absente ou mal formée.
     */
    fun parseTaken(taken: String): SimpleDate? {
        val match = Regex("^\\s*(\\d{1,2})/(\\d{1,2})/(\\d{4})").find(taken) ?: return null
        val (day, month, year) = match.destructured
        val date = SimpleDate(year.toInt(), month.toInt(), day.toInt())
        return date.takeIf { it.month in 1..12 && it.day in 1..31 }
    }

    /** « 18 nov. 2026 », pour les lignes de résultats. Sans date lisible : « date inconnue ». */
    fun long(taken: String): String {
        val date = parseTaken(taken) ?: return "date inconnue"
        return "${date.day} ${MONTHS[date.month - 1]} ${date.year}"
    }

    /** « 21 nov. », pour une date récente (dernière analyse). */
    fun short(date: SimpleDate): String = "${date.day} ${MONTHS[date.month - 1]}"
}
