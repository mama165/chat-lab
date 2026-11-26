package com.example.chatlab

/** The numbers shown next to each choice of the Filtres page. */
data class Facets(
    val total: Int,
    val datePresets: Map<DatePreset, Int>,
    /** Photos of the dates chosen by hand, or null if there are none. */
    val customRange: Int?,
    val places: Map<Place, Int>,
    val people: Map<People, Int>,
    /** Photos that would pass by turning on "Visage en gros plan". */
    val closeUp: Int,
    val folders: Map<String, Int>,
    val types: Map<String, Int>,
    val resemblance: Map<Resemblance, Int>,
)

/**
 * For each choice of a filter, the number of results we would get by choosing it, the other
 * filters staying as they are. We know this way, before tapping, what a choice will give.
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
            closeUp = count(filters.copy(closeUp = true)),
            folders = FilterOptions.folders(entries).associate { it.name to count(filters.copy(folders = setOf(it.name))) },
            types = FilterOptions.types(entries).associate { it.mime to count(filters.copy(types = setOf(it.mime))) },
            resemblance = Resemblance.entries.associateWith { count(filters.copy(resemblance = it)) },
        )
    }
}
