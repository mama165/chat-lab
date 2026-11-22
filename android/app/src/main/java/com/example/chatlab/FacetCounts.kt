package com.example.chatlab

/** Les nombres affichés à côté de chaque choix de la page Filtres. */
data class Facets(
    val total: Int,
    val datePresets: Map<DatePreset, Int>,
    /** Photos des dates choisies à la main, ou null s'il n'y en a pas. */
    val customRange: Int?,
    val places: Map<Place, Int>,
    val people: Map<People, Int>,
    val texts: Map<TextFilter, Int>,
    /** Photos qui passeraient en activant « Visage en gros plan ». */
    val closeUp: Int,
    val folders: Map<String, Int>,
    val types: Map<String, Int>,
    val resemblance: Map<Resemblance, Int>,
)

/**
 * Pour chaque choix d'un filtre, le nombre de résultats qu'on aurait en le choisissant, les autres
 * filtres restant ce qu'ils sont. On sait ainsi, avant d'appuyer, ce que donnera un choix.
 */
object FacetCounts {
    fun compute(
        entries: List<LibraryEntry>,
        query: String,
        ranked: List<Match>,
        filters: Filters,
        removed: Set<String>,
        time: When,
    ): Facets {
        fun count(f: Filters): Int =
            if (query.isBlank()) entries.count { f.matches(it, time) }
            else SearchResults.compute(entries, query, ranked, f, removed, 0, time).total

        val range = filters.date.range
        return Facets(
            total = count(filters),
            datePresets = DatePreset.entries.associateWith { count(filters.copy(date = DateFilter(it))) },
            customRange = range?.let { count(filters.copy(date = DateFilter(range = it))) },
            places = Place.entries.associateWith { count(filters.copy(place = it)) },
            people = People.entries.associateWith { count(filters.copy(people = it)) },
            texts = TextFilter.entries.associateWith { count(filters.copy(text = it)) },
            closeUp = count(filters.copy(closeUp = true)),
            folders = FilterOptions.folders(entries).associate { it.name to count(filters.copy(folders = setOf(it.name))) },
            types = FilterOptions.types(entries).associate { it.mime to count(filters.copy(types = setOf(it.mime))) },
            resemblance = Resemblance.entries.associateWith { count(filters.copy(resemblance = it)) },
        )
    }
}
