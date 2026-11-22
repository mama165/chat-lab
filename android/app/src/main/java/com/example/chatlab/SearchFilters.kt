package com.example.chatlab

import java.util.Calendar

/** Nombre de personnes visibles sur la photo. */
enum class People(val label: String) {
    ANY("Peu importe"),
    NONE("Aucune"),
    ONE("1"),
    MANY("2 et plus"),
}

/** Avec ou sans texte lu sur la photo. */
enum class TextFilter(val label: String) {
    ANY("Peu importe"),
    WITH("Avec texte"),
    WITHOUT("Sans texte"),
}

/** Fenêtres de temps glissantes, comptées en arrière à partir de maintenant. */
enum class DatePreset(val label: String) {
    ALL("Toutes les dates"),
    LAST_HOUR("Dernière heure"),
    LAST_24H("24 dernières heures"),
    LAST_7D("7 derniers jours"),
    LAST_30D("30 derniers jours"),
    LAST_12M("12 derniers mois"),
}

/** Moment de la journée, pour les dates choisies à la main. La nuit passe minuit. */
enum class DayPart(val label: String, val from: Int, val to: Int) {
    ALL("Toute la journée", 0, 1439),
    MORNING("Matin", 6 * 60, 12 * 60 - 1),
    AFTERNOON("Après-midi", 12 * 60, 18 * 60 - 1),
    EVENING("Soir", 18 * 60, 22 * 60 - 1),
    NIGHT("Nuit", 22 * 60, 6 * 60 - 1);

    /** Vrai si la minute de la journée [minute] est dans cette tranche. */
    fun contains(minute: Int): Boolean = if (from <= to) minute in from..to else minute >= from || minute <= to
}

/** Des dates choisies à la main : de [from] à [to] inclus, éventuellement un moment de la journée. */
data class DateRange(val from: SimpleDate, val to: SimpleDate, val dayPart: DayPart = DayPart.ALL) {
    init {
        require(from <= to) { "la date de début doit précéder la date de fin" }
    }
}

/** Le filtre de date : une fenêtre glissante, ou des dates choisies (qui l'emportent). */
data class DateFilter(val preset: DatePreset = DatePreset.ALL, val range: DateRange? = null) {
    val isActive: Boolean get() = preset != DatePreset.ALL || range != null

    /** Une photo passe si sa date de prise de vue est dans le filtre. Sans date, elle ne passe pas. */
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
        /** Le début et la fin de la fenêtre. Une photo « dans le futur » de moins d'une minute reste admise. */
        fun window(preset: DatePreset, time: When): Pair<Long, Long> {
            val end = time.now + 60_000L
            val start = when (preset) {
                DatePreset.ALL -> Long.MIN_VALUE
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
 * Point de départ des photos ressemblantes : le score minimal d'une photo pour être montrée
 * d'emblée. « Voir des photos moins proches » permet ensuite d'aller plus loin sans le changer.
 */
enum class Resemblance(val label: String, val floor: Float) {
    CLOSE("Proche", 0.11f),
    NORMAL("Normale", 0.08f),
    WIDE("Large", 0.05f),
}

/** Une étiquette de filtre actif, et les filtres qu'on obtient en la retirant. */
data class FilterChip(val label: String, val without: Filters)

/** Les filtres de la recherche. Chaque valeur par défaut veut dire « pas de filtre ». */
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
    val resemblance: Resemblance = Resemblance.NORMAL,
) {
    /** Nombre de filtres actifs : un par catégorie qui n'est plus sur sa valeur de départ. */
    val activeCount: Int
        get() = chips(WHEN_UNKNOWN).size

    val isDefault: Boolean
        get() = activeCount == 0

    /**
     * Les filtres actifs, dans l'ordre de la page Filtres, avec de quoi retirer chacun.
     * [time] sert à écrire les dates choisies (l'année n'apparaît que si elle n'est pas l'actuelle).
     */
    fun chips(time: When = WHEN_UNKNOWN): List<FilterChip> = buildList {
        if (date.isActive) add(FilterChip(dateLabel(time), copy(date = DateFilter())))
        if (place != Place.ALL) add(FilterChip(placeLabel(place), copy(place = Place.ALL)))
        if (people != People.ANY) add(FilterChip(peopleChip(people), copy(people = People.ANY)))
        if (closeUp) add(FilterChip("Gros plan", copy(closeUp = false)))
        if (text != TextFilter.ANY) add(FilterChip(text.label, copy(text = TextFilter.ANY)))
        if (folders.isNotEmpty()) add(FilterChip(folders.sorted().joinToString(", "), copy(folders = emptySet())))
        if (types.isNotEmpty()) {
            add(FilterChip(types.map { FileSniffer.label(it) }.sorted().joinToString(", "), copy(types = emptySet())))
        }
        if (resemblance != Resemblance.NORMAL) {
            add(FilterChip("Ressemblance ${resemblance.label.lowercase()}", copy(resemblance = Resemblance.NORMAL)))
        }
    }

    private fun dateLabel(time: When): String {
        val range = date.range
        if (range != null) {
            val days = Dates.rangeLabel(range.from, range.to, time.today)
            return if (range.dayPart == DayPart.ALL) days else "$days · ${range.dayPart.label}"
        }
        return date.preset.label
    }

    /** Une photo passe tous les filtres de contenu (la ressemblance ne filtre pas les photos). */
    fun matches(entry: LibraryEntry, time: When): Boolean =
        date.matches(entry.takenMs, time) &&
            Library.inPlace(entry, place) &&
            matchesPeople(entry.faces) &&
            (!closeUp || entry.closeUp) &&
            matchesText(entry.text) &&
            (folders.isEmpty() || entry.folder in folders) &&
            (types.isEmpty() || entry.mime in types)

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
        /** Un instant sans importance : pour compter les filtres sans écrire de date. */
        val WHEN_UNKNOWN = When(0L, java.util.TimeZone.getTimeZone("UTC"))

        fun placeLabel(place: Place): String = when (place) {
            Place.ALL -> "Partout"
            Place.INDOOR -> "Intérieur"
            Place.OUTDOOR -> "Extérieur"
        }

        private fun peopleChip(people: People): String = when (people) {
            People.ANY -> "Peu importe"
            People.NONE -> "Sans personne"
            People.ONE -> "1 personne"
            People.MANY -> "2 personnes et plus"
        }
    }
}
