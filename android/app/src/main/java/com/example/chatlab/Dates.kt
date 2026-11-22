package com.example.chatlab

import java.util.Calendar
import java.util.TimeZone

/** Un instant « maintenant » et le fuseau dans lequel on lit les dates : passés explicitement pour tester. */
data class When(val now: Long, val zone: TimeZone = TimeZone.getDefault()) {
    val today: SimpleDate get() = SimpleDate.of(now, zone)
}

/** Une date sans heure, dans le fuseau du téléphone. Comparable : 2026-11-09 < 2026-11-22. */
data class SimpleDate(val year: Int, val month: Int, val day: Int) : Comparable<SimpleDate> {
    override fun compareTo(other: SimpleDate): Int =
        compareValuesBy(this, other, { it.year }, { it.month }, { it.day })

    /** Minuit, début du jour, dans le fuseau donné. */
    fun startMillis(zone: TimeZone): Long = Calendar.getInstance(zone).apply {
        clear()
        set(year, month - 1, day, 0, 0, 0)
    }.timeInMillis

    /** Dernière milliseconde du jour. */
    fun endMillis(zone: TimeZone): Long = plusDays(1, zone).startMillis(zone) - 1

    fun plusDays(days: Int, zone: TimeZone = TimeZone.getDefault()): SimpleDate {
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
            add(Calendar.DAY_OF_MONTH, days)
        }
        return of(calendar.timeInMillis, zone)
    }

    /** Le jour de la semaine, de 0 (lundi) à 6 (dimanche). */
    fun weekday(zone: TimeZone = TimeZone.getDefault()): Int {
        val calendar = Calendar.getInstance(zone).apply {
            clear()
            set(year, month - 1, day, 12, 0, 0)
        }
        return (calendar.get(Calendar.DAY_OF_WEEK) + 5) % 7
    }

    companion object {
        fun of(millis: Long, zone: TimeZone = TimeZone.getDefault()): SimpleDate {
            val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
            return SimpleDate(
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH) + 1,
                calendar.get(Calendar.DAY_OF_MONTH),
            )
        }

        /** Nombre de jours d'un mois (février des années bissextiles compris). */
        fun daysIn(year: Int, month: Int): Int = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(year, month - 1, 1)
        }.getActualMaximum(Calendar.DAY_OF_MONTH)
    }
}

object Dates {
    val MONTHS = listOf(
        "janv.", "févr.", "mars", "avr.", "mai", "juin",
        "juil.", "août", "sept.", "oct.", "nov.", "déc.",
    )

    val MONTH_NAMES = listOf(
        "Janvier", "Février", "Mars", "Avril", "Mai", "Juin",
        "Juillet", "Août", "Septembre", "Octobre", "Novembre", "Décembre",
    )

    private val TAKEN = Regex("^\\s*(\\d{1,2})/(\\d{1,2})/(\\d{4})(?:\\s+(\\d{1,2}):(\\d{2}))?")

    /**
     * Lit la date d'une photo telle que l'analyse l'écrit, « jj/mm/aaaa » suivi ou non de l'heure.
     * Renvoie null pour une date absente ou mal formée.
     */
    fun parseTaken(taken: String): SimpleDate? {
        val match = TAKEN.find(taken) ?: return null
        val (day, month, year) = match.destructured
        val date = SimpleDate(year.toInt(), month.toInt(), day.toInt())
        return date.takeIf { it.month in 1..12 && it.day in 1..SimpleDate.daysIn(it.year, it.month) }
    }

    /** L'instant d'une date « jj/mm/aaaa hh:mm » (minuit si l'heure manque), ou null si illisible. */
    fun parseTakenMillis(taken: String, zone: TimeZone): Long? {
        val date = parseTaken(taken) ?: return null
        val match = TAKEN.find(taken)!!
        val hour = match.groupValues[4].toIntOrNull() ?: 0
        val minute = match.groupValues[5].toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return date.startMillis(zone) + (hour * 60L + minute) * 60_000L
    }

    /** Convertit la date EXIF « aaaa:mm:jj hh:mm:ss » en « jj/mm/aaaa hh:mm », ou null si illisible. */
    fun exifToTaken(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val parts = raw.trim().split(' ')
        val date = parts[0].split(':')
        if (date.size != 3 || date.any { it.toIntOrNull() == null }) return null
        val (year, month, day) = date.map { it.toInt() }
        if (year < 1900 || month !in 1..12 || day !in 1..SimpleDate.daysIn(year, month)) return null
        val time = parts.getOrNull(1)?.split(':')
        val hour = time?.getOrNull(0)?.toIntOrNull() ?: 0
        val minute = time?.getOrNull(1)?.toIntOrNull() ?: 0
        if (hour !in 0..23 || minute !in 0..59) return null
        return "%02d/%02d/%04d %02d:%02d".format(day, month, year, hour, minute)
    }

    /** « 18/11/2026 13:42 » : le format du carnet. */
    fun formatTaken(millis: Long, zone: TimeZone): String {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return "%02d/%02d/%04d %02d:%02d".format(
            calendar.get(Calendar.DAY_OF_MONTH),
            calendar.get(Calendar.MONTH) + 1,
            calendar.get(Calendar.YEAR),
            calendar.get(Calendar.HOUR_OF_DAY),
            calendar.get(Calendar.MINUTE),
        )
    }

    /** Minute de la journée, de 0 à 1439. */
    fun minuteOfDay(millis: Long, zone: TimeZone): Int {
        val calendar = Calendar.getInstance(zone).apply { timeInMillis = millis }
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE)
    }

    /** « 18 nov. 2026 », pour les lignes de résultats. Sans date lisible : « date inconnue ». */
    fun long(taken: String): String {
        val date = parseTaken(taken) ?: return "date inconnue"
        return "${date.day} ${MONTHS[date.month - 1]} ${date.year}"
    }

    /** « 21 nov. », pour une date récente (dernière analyse). */
    fun short(date: SimpleDate): String = "${date.day} ${MONTHS[date.month - 1]}"

    /**
     * La date d'une photo dans une liste : « Aujourd'hui, 13:42 », « Hier, 09:15 », sinon
     * « 18 nov. 2026 ». [takenMs] vaut 0 quand l'heure exacte est inconnue : on retombe sur [taken].
     */
    fun display(takenMs: Long, taken: String, time: When): String {
        if (takenMs <= 0) return long(taken)
        val day = SimpleDate.of(takenMs, time.zone)
        val minutes = minuteOfDay(takenMs, time.zone)
        val clock = "%02d:%02d".format(minutes / 60, minutes % 60)
        return when (day) {
            time.today -> "Aujourd'hui, $clock"
            time.today.plusDays(-1, time.zone) -> "Hier, $clock"
            else -> "${day.day} ${MONTHS[day.month - 1]} ${day.year}"
        }
    }

    /** « 9 – 22 nov. », « 28 oct. – 3 nov. », ou avec l'année quand elle diffère d'aujourd'hui. */
    fun rangeLabel(from: SimpleDate, to: SimpleDate, today: SimpleDate): String {
        if (from == to) return dayLabel(from, today)
        val sameYear = from.year == to.year
        if (sameYear && from.year == today.year) {
            return if (from.month == to.month) "${from.day} – ${to.day} ${MONTHS[to.month - 1]}"
            else "${from.day} ${MONTHS[from.month - 1]} – ${to.day} ${MONTHS[to.month - 1]}"
        }
        return "${from.day} ${MONTHS[from.month - 1]} ${from.year} – ${to.day} ${MONTHS[to.month - 1]} ${to.year}"
    }

    private fun dayLabel(date: SimpleDate, today: SimpleDate): String =
        if (date.year == today.year) "${date.day} ${MONTHS[date.month - 1]}"
        else "${date.day} ${MONTHS[date.month - 1]} ${date.year}"
}
