package com.example.chatlab

import java.util.Calendar

/** Number of people visible in the photo. */
enum class People(val label: String) {
    ANY("Peu importe"),
    NONE("Aucune"),
    ONE("1"),
    MANY("2 et plus"),
}

/** With or without text read on the photo. */
enum class TextFilter(val label: String) {
    ANY("Peu importe"),
    WITH("Avec texte"),
    WITHOUT("Sans texte"),
}

/** Sliding time windows, counted backwards from now. */
enum class DatePreset(val label: String) {
    ALL("Toutes les dates"),
    TODAY("Aujourd'hui"),
    LAST_HOUR("Dernière heure"),
    LAST_24H("24 dernières heures"),
    LAST_7D("7 derniers jours"),
    LAST_30D("30 derniers jours"),
    LAST_12M("12 derniers mois"),
}

/** Kind of file, from the bytes of the file: "Image" groups the picture formats, "PDF" the documents. */
enum class FileKind(val label: String) {
    ANY("Tous les types"),
    IMAGE("Image"),
    PDF("PDF");

    fun matches(mime: String?): Boolean = when (this) {
        ANY -> true
        IMAGE -> mime != null && mime in FileSniffer.PICTURES
        PDF -> mime == FileSniffer.PDF
    }
}

/** Time of the day, for the dates chosen by hand. The night goes past midnight. */
enum class DayPart(val label: String, val from: Int, val to: Int) {
    ALL("Toute la journée", 0, 1439),
    MORNING("Matin", 6 * 60, 12 * 60 - 1),
    AFTERNOON("Après-midi", 12 * 60, 18 * 60 - 1),
    EVENING("Soir", 18 * 60, 22 * 60 - 1),
    NIGHT("Nuit", 22 * 60, 6 * 60 - 1);

    /** True if the minute of the day [minute] is in this slot. */
    fun contains(minute: Int): Boolean = if (from <= to) minute in from..to else minute >= from || minute <= to
}

/** Dates chosen by hand: from [from] to [to] included, possibly a time of the day. */
data class DateRange(val from: SimpleDate, val to: SimpleDate, val dayPart: DayPart = DayPart.ALL) {
    init {
        require(from <= to) { "la date de début doit précéder la date de fin" }
    }
}

/** The date filter: a sliding window, or dates chosen (which take precedence). */
data class DateFilter(val preset: DatePreset = DatePreset.ALL, val range: DateRange? = null) {
    val isActive: Boolean get() = preset != DatePreset.ALL || range != null

    /** A photo passes if its shooting date is in the filter. Without a date, it does not pass. */
    fun matches(takenMs: Long, time: When): Boolean {
        if (!isActive) return true
        if (takenMs <= 0) return false
        range?.let { r ->
            val day = SimpleDate.of(takenMs, time.zone)
            return day >= r.from && day <= r.to && r.dayPart.contains(Dates.minuteOfDay(takenMs, time.zone))
        }
        val (start, end) = window(preset, time)
        return takenMs in start..end
    }

    companion object {
        /** The start and end of the window. A photo "in the future" by less than a minute stays admise. */
        fun window(preset: DatePreset, time: When): Pair<Long, Long> {
            val end = time.now + 60_000L
            val start = when (preset) {
                DatePreset.ALL -> Long.MIN_VALUE
                DatePreset.TODAY -> Calendar.getInstance(time.zone).apply {
                    timeInMillis = time.now
                    set(Calendar.HOUR_OF_DAY, 0)
                    set(Calendar.MINUTE, 0)
                    set(Calendar.SECOND, 0)
                    set(Calendar.MILLISECOND, 0)
                }.timeInMillis
                DatePreset.LAST_HOUR -> time.now - 3_600_000L
                DatePreset.LAST_24H -> time.now - 24 * 3_600_000L
                DatePreset.LAST_7D -> time.now - 7 * 24 * 3_600_000L
                DatePreset.LAST_30D -> time.now - 30 * 24 * 3_600_000L
                DatePreset.LAST_12M -> Calendar.getInstance(time.zone).apply {
                    timeInMillis = time.now
                    add(Calendar.MONTH, -12)
                }.timeInMillis
            }
            return start to end
        }
    }
}

/**
 * Starting point of the similar photos: the minimum score for a photo to be shown
 * straight away. "Voir des photos moins proches" then goes further without changing it.
 */
enum class Resemblance(val label: String, val floor: Float) {
    CLOSE("Proche", 0.11f),
    NORMAL("Normale", 0.08f),
    WIDE("Large", 0.05f),
}

/** A label of an active filter, and the filters we get by removing it. */
data class FilterChip(val label: String, val without: Filters)

/** The filters of the search. Each default value means "no filter". */
data class Filters(
    val date: DateFilter = DateFilter(),
    val place: Place = Place.ALL,
    val people: People = People.ANY,
    val closeUp: Boolean = false,
    val text: TextFilter = TextFilter.ANY,
    /** Noms de dossiers choisis ; vide veut dire tous. */
    val folders: Set<String> = emptySet(),
    /** Types MIME choisis ; vide veut dire tous. */
    val types: Set<String> = emptySet(),
    /** Image or PDF, as in the Type filter of the search sheet. */
    val kind: FileKind = FileKind.ANY,
    val resemblance: Resemblance = Resemblance.NORMAL,
) {
    /** Number of active filters: one per category that is no longer on its starting value. */
    val activeCount: Int
        get() = chips(WHEN_UNKNOWN).size

    val isDefault: Boolean
        get() = activeCount == 0

    /**
     * The active filters, in the order of the Filtres page, with what is needed to remove each.
     * [time] is used to write the dates chosen (the year appears only if it is not the current one)).
     */
    fun chips(time: When = WHEN_UNKNOWN): List<FilterChip> = buildList {
        if (date.isActive) add(FilterChip("Date · ${dateLabel(time)}", copy(date = DateFilter())))
        if (place != Place.ALL) add(FilterChip("Lieu · ${placeLabel(place)}", copy(place = Place.ALL)))
        if (people != People.ANY) add(FilterChip("Personnes · ${people.label}", copy(people = People.ANY)))
        if (closeUp) add(FilterChip("Gros plan", copy(closeUp = false)))
        if (text != TextFilter.ANY) add(FilterChip(text.label, copy(text = TextFilter.ANY)))
        if (folders.isNotEmpty()) add(FilterChip(folders.sorted().joinToString(", "), copy(folders = emptySet())))
        if (types.isNotEmpty()) {
            add(FilterChip(types.map { FileSniffer.label(it) }.sorted().joinToString(", "), copy(types = emptySet())))
        }
        if (kind != FileKind.ANY) add(FilterChip("Type · ${kind.label}", copy(kind = FileKind.ANY)))
        if (resemblance != Resemblance.NORMAL) {
            add(FilterChip("Ressemblance ${resemblance.label.lowercase()}", copy(resemblance = Resemblance.NORMAL)))
        }
    }

    /** The filters that the search screen applies: the place is not applied yet. */
    fun withoutPlace(): Filters = copy(place = Place.ALL)

    private fun dateLabel(time: When): String {
        val range = date.range
        if (range != null) {
            val days = Dates.rangeLabel(range.from, range.to, time.today)
            return if (range.dayPart == DayPart.ALL) days else "$days · ${range.dayPart.label}"
        }
        return date.preset.label
    }

    /**
     * A photo passes all the content filters (the resemblance does not filter the photos).
     * The search screen leaves the place out for now (see [withoutPlace]): the counts keep it.
     */
    fun matches(entry: LibraryEntry, time: When): Boolean =
        date.matches(entry.takenMs, time) &&
            Library.inPlace(entry, place) &&
            matchesPeople(entry.faces) &&
            (!closeUp || entry.closeUp) &&
            matchesText(entry.text) &&
            (folders.isEmpty() || entry.folder in folders) &&
            (types.isEmpty() || entry.mime in types) &&
            kind.matches(entry.mime)

    private fun matchesPeople(faces: Int): Boolean = when (people) {
        People.ANY -> true
        People.NONE -> faces == 0
        People.ONE -> faces == 1
        People.MANY -> faces >= 2
    }

    private fun matchesText(text: String): Boolean = when (this.text) {
        TextFilter.ANY -> true
        TextFilter.WITH -> text.isNotBlank()
        TextFilter.WITHOUT -> text.isBlank()
    }

    companion object {
        /** An unimportant instant: to count the filters without writing a date. */
        val WHEN_UNKNOWN = When(0L, java.util.TimeZone.getTimeZone("UTC"))

        fun placeLabel(place: Place): String = when (place) {
            Place.ALL -> "Partout"
            Place.INDOOR -> "Intérieur"
            Place.OUTDOOR -> "Extérieur"
        }

    }
}
