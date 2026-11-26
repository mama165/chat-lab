package com.example.chatlab

import java.text.Normalizer

/** A date written in the search box, and the rest of the text once that date is taken out. */
data class TypedDate(val range: DateRange?, val rest: String)

/**
 * Reads the dates typed in the search box: "hier", "avant-hier", "aujourd'hui", "ce matin", "hier soir",
 * "26 novembre 2026", "26 nov." (this year, or last year if still to come), "26/11/2026", "2026-11-26",
 * "novembre 2026", "avant le 20 novembre" (strictly before), "après le 20 novembre" (strictly after),
 * "entre le 3 et le 10 novembre" or "du 3 au 10 novembre" (both days included).
 * Accents and capitals do not matter. What is not a date stays as the words to look for.
 */
object TypedDates {
    private val MONTHS = mapOf(
        "janvier" to 1, "janv" to 1, "fevrier" to 2, "fevr" to 2, "fev" to 2, "mars" to 3,
        "avril" to 4, "avr" to 4, "mai" to 5, "juin" to 6, "juillet" to 7, "juil" to 7,
        "aout" to 8, "septembre" to 9, "sept" to 9, "octobre" to 10, "oct" to 10,
        "novembre" to 11, "nov" to 11, "decembre" to 12, "dec" to 12,
    )
    private val PARTS = mapOf(
        "matin" to DayPart.MORNING,
        "apres-midi" to DayPart.AFTERNOON,
        "soir" to DayPart.EVENING,
        "nuit" to DayPart.NIGHT,
    )
    private val MONTH = MONTHS.keys.sortedByDescending { it.length }.joinToString("|")
    private const val PART = "matin|apres-midi|soir|nuit"

    /** A date written out in full (no "hier" here): used after "avant", "après", "entre". */
    private val EXPLICIT = "\\d{1,2}\\s+(?:$MONTH)\\.?(?:\\s+\\d{4})?|\\d{1,2}/\\d{1,2}(?:/\\d{4})?|\\d{4}-\\d{1,2}-\\d{1,2}"

    private val THIS = Regex("(?<![a-z])(?:ce|cet|cette)\\s+($PART)(?![a-z])")
    private val BETWEEN = Regex(
        "(?<![a-z])(?:entre\\s+(?:le\\s+)?|du\\s+)(\\d{1,2})(?:\\s+($MONTH)\\.?(?:\\s+(\\d{4}))?)?" +
            "\\s+(?:et|au|a)\\s+(?:le\\s+)?(\\d{1,2})\\s+($MONTH)\\.?(?:\\s+(\\d{4}))?(?![a-z0-9])",
    )
    private val BEFORE_AFTER = Regex("(?<![a-z])(avant|apres)\\s+(?:le\\s+)?($EXPLICIT)(?![a-z0-9])")
    private val RELATIVE = Regex("(?<![a-z])(avant[- ]hier|hier|aujourd'?hui)(?:\\s+($PART))?(?![a-z])")
    private val ISO = Regex("(?<![0-9])(\\d{4})-(\\d{1,2})-(\\d{1,2})(?![0-9])")
    private val NUMERIC = Regex("(?<![0-9])(\\d{1,2})/(\\d{1,2})(?:/(\\d{4}))?(?![0-9])")
    private val DAY_MONTH = Regex("(?<![a-z0-9])(\\d{1,2})\\s+($MONTH)\\.?(?:\\s+(\\d{4}))?(?![a-z0-9])")
    private val MONTH_YEAR = Regex("(?<![a-z0-9])($MONTH)\\.?\\s+(\\d{4})(?![a-z0-9])")

    /** Open ends of "avant" and "après": far enough to hold any photo. */
    private val EARLIEST = SimpleDate(1970, 1, 1)
    private val LATEST = SimpleDate(2100, 12, 31)

    fun parse(text: String, time: When): TypedDate {
        // Each character keeps its place: the dates found in the folded text are cut from the original one.
        val folded = fold(text)
        val consumed = BooleanArray(text.length)
        var range: DateRange? = null

        fun claim(match: MatchResult, found: DateRange?) {
            if (found == null || match.range.any { consumed[it] }) return
            match.range.forEach { consumed[it] = true }
            if (range == null) range = found
        }

        for (match in THIS.findAll(folded)) {
            claim(match, single(time.today, PARTS[match.groupValues[1]] ?: DayPart.ALL))
        }
        for (match in BETWEEN.findAll(folded)) {
            claim(match, between(match, time))
        }
        for (match in BEFORE_AFTER.findAll(folded)) {
            claim(match, beforeOrAfter(match, time))
        }
        for (match in RELATIVE.findAll(folded)) {
            claim(match, relative(match, time))
        }
        for (match in ISO.findAll(folded)) {
            val (year, month, day) = match.destructured
            claim(match, dayOf(year.toInt(), month.toInt(), day.toInt())?.let { single(it, DayPart.ALL) })
        }
        for (match in NUMERIC.findAll(folded)) {
            val (day, month, year) = match.destructured
            claim(match, yearFor(month.toInt(), day.toInt(), year.ifEmpty { null }, time)?.let { single(it, DayPart.ALL) })
        }
        for (match in DAY_MONTH.findAll(folded)) {
            val (day, month, year) = match.destructured
            claim(match, yearFor(MONTHS.getValue(month), day.toInt(), year.ifEmpty { null }, time)?.let { single(it, DayPart.ALL) })
        }
        for (match in MONTH_YEAR.findAll(folded)) {
            val (month, year) = match.destructured
            val number = MONTHS.getValue(month)
            val y = year.toInt()
            claim(match, DateRange(SimpleDate(y, number, 1), SimpleDate(y, number, SimpleDate.daysIn(y, number))))
        }

        val rest = buildString {
            text.forEachIndexed { index, c -> if (!consumed[index]) append(c) }
        }.replace(Regex("\\s+"), " ").trim()
        return TypedDate(range, rest)
    }

    /** "avant le 20 novembre": every day up to the day before. "après le 20 novembre": from the day after. */
    private fun beforeOrAfter(match: MatchResult, time: When): DateRange? {
        val day = parse(match.groupValues[2], time).range?.from ?: return null
        return if (match.groupValues[1] == "avant") {
            DateRange(EARLIEST, day.plusDays(-1, time.zone))
        } else {
            DateRange(day.plusDays(1, time.zone), LATEST)
        }
    }

    /** "entre le 3 et le 10 novembre" or "du 25 octobre au 3 novembre 2026": both ends included. */
    private fun between(match: MatchResult, time: When): DateRange? {
        val g = match.groupValues
        val endMonth = MONTHS.getValue(g[5])
        val end = yearFor(endMonth, g[4].toInt(), g[6].ifEmpty { null }, time) ?: return null
        val start = if (g[2].isEmpty()) {
            // The month and year of the end are the ones of the start.
            dayOf(end.year, end.month, g[1].toInt())
        } else {
            yearFor(MONTHS.getValue(g[2]), g[1].toInt(), g[3].ifEmpty { null }, time)
        } ?: return null
        if (start > end) return null
        return DateRange(start, end)
    }

    private fun relative(match: MatchResult, time: When): DateRange {
        val word = match.groupValues[1].replace(' ', '-')
        val offset = when {
            word.startsWith("avant") -> -2
            word == "hier" -> -1
            else -> 0
        }
        return single(time.today.plusDays(offset, time.zone), PARTS[match.groupValues[2]] ?: DayPart.ALL)
    }

    private fun single(day: SimpleDate, part: DayPart): DateRange = DateRange(day, day, part)

    /** A date without year: this year, or last year when this year's date is still to come. */
    private fun yearFor(month: Int, day: Int, year: String?, time: When): SimpleDate? {
        if (year != null) return dayOf(year.toInt(), month, day)
        val thisYear = time.today.year
        return dayOf(thisYear, month, day)?.takeIf { it <= time.today } ?: dayOf(thisYear - 1, month, day)
    }

    private fun dayOf(year: Int, month: Int, day: Int): SimpleDate? =
        SimpleDate(year, month, day).takeIf { month in 1..12 && day in 1..SimpleDate.daysIn(year, month) }

    /** Lower case, without accents, one character for one character; the typographic apostrophe is kept as a plain one. */
    private fun fold(text: String): String = buildString {
        for (c in text) {
            val base = if (c == '’') '\'' else Normalizer.normalize(c.toString(), Normalizer.Form.NFD).first()
            append(base.lowercaseChar())
        }
    }
}

/** The filters with a date typed in the search box: it replaces the date chosen in the sheet. */
fun Filters.withTypedDate(range: DateRange?): Filters =
    if (range == null) this else copy(date = DateFilter(range = range))
