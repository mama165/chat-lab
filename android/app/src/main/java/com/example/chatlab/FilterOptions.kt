package com.example.chatlab

import java.util.TimeZone

data class FolderOption(val name: String, val count: Int)
data class TypeOption(val mime: String, val count: Int)

/** Les choix proposés par la page Filtres : seulement ce qui existe vraiment dans le carnet. */
object FilterOptions {
    /** Les dossiers qui ont au moins une photo dans le carnet, avec leur nombre de photos. */
    fun folders(entries: List<LibraryEntry>): List<FolderOption> =
        entries.filter { it.folder.isNotBlank() }
            .groupingBy { it.folder }.eachCount()
            .map { FolderOption(it.key, it.value) }
            .sortedBy { it.name.lowercase() }

    /**
     * Les types trouvés dans le carnet et que l'application sait lire. Un type absent du carnet,
     * ou qu'on ne sait pas analyser (PDF aujourd'hui), n'est jamais proposé.
     */
    fun types(entries: List<LibraryEntry>): List<TypeOption> =
        entries.filter { FileSniffer.isSupported(it.mime) }
            .groupingBy { it.mime }.eachCount()
            .map { TypeOption(it.key, it.value) }
            .sortedBy { FileSniffer.label(it.mime) }

    /** Les jours qui ont au moins une photo : les points du calendrier. */
    fun photoDays(entries: List<LibraryEntry>, zone: TimeZone): Set<SimpleDate> =
        entries.filter { it.takenMs > 0 }.map { SimpleDate.of(it.takenMs, zone) }.toSet()
}

/** Une case du calendrier : un jour, ou du vide avant le premier du mois. */
data class CalendarCell(val day: SimpleDate?, val hasPhotos: Boolean = false)

/** Un mois du calendrier, semaines du lundi au dimanche. */
object CalendarGrid {
    val WEEKDAYS = listOf("L", "M", "M", "J", "V", "S", "D")

    /** Les semaines du mois, chacune de 7 cases ; la dernière est complétée de vide. */
    fun month(year: Int, month: Int, photoDays: Set<SimpleDate>, zone: TimeZone): List<List<CalendarCell>> {
        val first = SimpleDate(year, month, 1)
        val cells = ArrayList<CalendarCell>()
        repeat(first.weekday(zone)) { cells += CalendarCell(null) }
        for (day in 1..SimpleDate.daysIn(year, month)) {
            val date = SimpleDate(year, month, day)
            cells += CalendarCell(date, date in photoDays)
        }
        while (cells.size % 7 != 0) cells += CalendarCell(null)
        return cells.chunked(7)
    }

    fun previous(year: Int, month: Int): Pair<Int, Int> = if (month == 1) year - 1 to 12 else year to month - 1

    fun next(year: Int, month: Int): Pair<Int, Int> = if (month == 12) year + 1 to 1 else year to month + 1

    fun title(year: Int, month: Int): String = "${Dates.MONTH_NAMES[month - 1]} $year"
}

/** Le choix des dates sur le calendrier : un appui pose le début, le suivant la fin. */
data class DateSelection(val from: SimpleDate? = null, val to: SimpleDate? = null) {
    /** Un appui sur un jour : début, puis fin (échangés si on remonte), puis un nouveau début. */
    fun tap(day: SimpleDate): DateSelection = when {
        from == null || to != null -> DateSelection(day, null)
        day >= from -> DateSelection(from, day)
        else -> DateSelection(day, from)
    }

    /** Vrai si le jour est dans la sélection (début seul : ce jour-là). */
    fun contains(day: SimpleDate): Boolean {
        val start = from ?: return false
        val end = to ?: start
        return day >= start && day <= end
    }

    /** Les dates choisies, ou null tant qu'aucun jour n'est posé. Un seul jour vaut un jour entier. */
    fun toRange(dayPart: DayPart = DayPart.ALL): DateRange? =
        from?.let { DateRange(it, to ?: it, dayPart) }

    companion object {
        fun of(range: DateRange?): DateSelection = DateSelection(range?.from, range?.to)
    }
}

/** Les raccourcis au-dessus du calendrier. */
enum class DateShortcut(val label: String) {
    TODAY("Aujourd'hui"),
    YESTERDAY("Hier"),
    WEEKEND("Ce week-end"),
    THIS_MONTH("Ce mois"),
    LAST_MONTH("Mois dernier");

    fun range(time: When): DateRange {
        val today = time.today
        return when (this) {
            TODAY -> DateRange(today, today)
            YESTERDAY -> today.plusDays(-1, time.zone).let { DateRange(it, it) }
            WEEKEND -> {
                // Le week-end en cours s'il est commencé (samedi ou dimanche), sinon le dernier.
                val weekday = today.weekday(time.zone)
                val saturday = when {
                    weekday == 5 -> today
                    weekday == 6 -> today.plusDays(-1, time.zone)
                    else -> today.plusDays(-(weekday + 2), time.zone)
                }
                DateRange(saturday, saturday.plusDays(1, time.zone).let { if (it > today) today else it })
            }
            THIS_MONTH -> DateRange(SimpleDate(today.year, today.month, 1), today)
            LAST_MONTH -> {
                val (year, month) = CalendarGrid.previous(today.year, today.month)
                DateRange(SimpleDate(year, month, 1), SimpleDate(year, month, SimpleDate.daysIn(year, month)))
            }
        }
    }
}
