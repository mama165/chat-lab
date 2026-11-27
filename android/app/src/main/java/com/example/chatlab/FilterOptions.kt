package com.example.chatlab

import java.util.TimeZone

data class FolderOption(val name: String, val count: Int)
data class TypeOption(val mime: String, val count: Int)

/** The choices offered by the Filtres page: only what really exists in the library. */
object FilterOptions {
    /** The folders that have at least one photo in the library, with their number of photos. */
    fun folders(entries: List<LibraryEntry>): List<FolderOption> =
        entries.filter { it.folder.isNotBlank() }
            .groupingBy { it.folder }.eachCount()
            .map { FolderOption(it.key, it.value) }
            .sortedBy { it.name.lowercase() }

    /**
     * The types found in the library and that the application can read. A type absent from the library,
     * or that we cannot analyse (PDF today), is never offered.
     */
    fun types(entries: List<LibraryEntry>): List<TypeOption> =
        entries.filter { FileSniffer.isSupported(it.mime) }
            .groupingBy { it.mime }.eachCount()
            .map { TypeOption(it.key, it.value) }
            .sortedBy { FileSniffer.label(it.mime) }

    /** The days that have at least one photo: the dots of the calendar. */
    fun photoDays(entries: List<LibraryEntry>, zone: TimeZone): Set<SimpleDate> =
        entries.filter { it.takenMs > 0 }.map { SimpleDate.of(it.takenMs, zone) }.toSet()
}

/** A cell of the calendar: a day, or empty space before the first of the month. */
data class CalendarCell(val day: SimpleDate?, val hasPhotos: Boolean = false)

/** A month of the calendar, weeks from Monday to Sunday. */
object CalendarGrid {
    val WEEKDAYS = listOf("L", "M", "M", "J", "V", "S", "D")

    /** The weeks of the month, each of 7 cells; the last one is padded with empty cells. */
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

/** The choice of dates on the calendar: a tap sets the start, the next one the end. */
data class DateSelection(val from: SimpleDate? = null, val to: SimpleDate? = null) {
    /** A tap on a day: start, then end (swapped if we go backwards), then a new start. */
    fun tap(day: SimpleDate): DateSelection = when {
        // A second tap on the only day chosen cancels it: no date filter any more.
        from == day && to == null -> DateSelection()
        from == null || to != null -> DateSelection(day, null)
        day >= from -> DateSelection(from, day)
        else -> DateSelection(day, from)
    }

    /** True if the day is in the selection (start only: that day). */
    fun contains(day: SimpleDate): Boolean {
        val start = from ?: return false
        val end = to ?: start
        return day >= start && day <= end
    }

    /** The chosen dates, or null while no day is set. A single day means a whole day. */
    fun toRange(dayPart: DayPart = DayPart.ALL): DateRange? =
        from?.let { DateRange(it, to ?: it, dayPart) }

    companion object {
        fun of(range: DateRange?): DateSelection = DateSelection(range?.from, range?.to)
    }
}

/** The shortcuts above the calendar. */
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
                // The current weekend if it has started (Saturday or Sunday), otherwise the last one.
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
