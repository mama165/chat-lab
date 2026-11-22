package com.example.chatlab

/** Nombre de personnes visibles sur la photo. */
enum class People(val label: String) {
    ANY("Peu importe"),
    NONE("Aucune"),
    ONE("1"),
    MANY("2 et plus"),
}

/** Période de prise de vue, comptée à partir d'aujourd'hui. */
enum class Period(val label: String) {
    ALL("Tout"),
    THIS_MONTH("Ce mois"),
    THIS_YEAR("Cette année"),
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
    val place: Place = Place.ALL,
    val people: People = People.ANY,
    val period: Period = Period.ALL,
    val resemblance: Resemblance = Resemblance.NORMAL,
) {
    /** Nombre de filtres actifs : ceux qui ne sont plus sur leur valeur de départ. */
    val activeCount: Int
        get() = chips().size

    val isDefault: Boolean
        get() = activeCount == 0

    /** Les filtres actifs, dans l'ordre de la page Filtres, avec de quoi retirer chacun. */
    fun chips(): List<FilterChip> = buildList {
        if (place != Place.ALL) add(FilterChip(placeLabel(place), copy(place = Place.ALL)))
        if (people != People.ANY) add(FilterChip(peopleChip(people), copy(people = People.ANY)))
        if (period != Period.ALL) add(FilterChip(period.label, copy(period = Period.ALL)))
        if (resemblance != Resemblance.NORMAL) {
            add(FilterChip("Ressemblance ${resemblance.label.lowercase()}", copy(resemblance = Resemblance.NORMAL)))
        }
    }

    /** Une photo passe les filtres de lieu, de personnes et de période. */
    fun matches(entry: LibraryEntry, today: SimpleDate): Boolean =
        Library.inPlace(entry, place) && matchesPeople(entry.faces) && matchesPeriod(entry.taken, today)

    private fun matchesPeople(faces: Int): Boolean = when (people) {
        People.ANY -> true
        People.NONE -> faces == 0
        People.ONE -> faces == 1
        People.MANY -> faces >= 2
    }

    /** Une photo sans date lisible ne passe que le filtre « Tout ». */
    private fun matchesPeriod(taken: String, today: SimpleDate): Boolean {
        if (period == Period.ALL) return true
        val date = Dates.parseTaken(taken) ?: return false
        return when (period) {
            Period.ALL -> true
            Period.THIS_MONTH -> date.year == today.year && date.month == today.month
            Period.THIS_YEAR -> date.year == today.year
        }
    }

    companion object {
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
