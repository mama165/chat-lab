package com.example.chatlab

/**
 * Ce que l'écran de recherche affiche pour une requête :
 * - [words] : les photos dont le texte lu (ou le nom) contient tous les mots ;
 * - [close] : les photos ressemblantes, au-dessus du point de départ choisi ;
 * - [extra] : les photos « moins proches » déjà dévoilées, au compte-gouttes.
 * Une photo n'apparaît qu'une fois, et jamais si elle a été retirée de cette recherche.
 */
data class SearchOutcome(
    val words: List<Match>,
    val close: List<Match>,
    val extra: List<Match>,
    /** Photos moins proches encore disponibles, dans la limite de [MoreResults.MAX_EXTRA]. */
    val remaining: Int,
    /** Vrai si la limite de photos moins proches a été atteinte alors qu'il en restait. */
    val limitReached: Boolean,
) {
    val total: Int get() = words.size + close.size + extra.size
    val isEmpty: Boolean get() = total == 0
    val hasMore: Boolean get() = remaining > 0

    /** Nombre de photos qu'ajoutera le prochain appui sur « Voir des photos moins proches ». */
    val nextBatch: Int get() = minOf(MoreResults.BATCH, remaining)

    companion object {
        val EMPTY = SearchOutcome(emptyList(), emptyList(), emptyList(), 0, false)
    }
}

/** Réglages du compte-gouttes « Voir des photos moins proches ». */
object MoreResults {
    /** Photos ajoutées à chaque appui. */
    const val BATCH = 6

    /** Au plus ce nombre de photos moins proches pour une recherche. */
    const val MAX_EXTRA = 30

    /** En dessous de ce score, une photo ne ressemble plus assez pour être proposée. */
    const val FLOOR = 0.03f
}

object SearchResults {
    /** Clé d'une photo : son adresse d'origine, ou son chemin à défaut. */
    fun keyOf(match: Match): String = match.entry.source.ifBlank { match.entry.path }

    /**
     * Calcule les résultats. [ranked] est le classement complet par description (vide si la
     * description n'est pas encore lue) ; [shownExtra] le nombre de photos moins proches déjà
     * dévoilées.
     */
    fun compute(
        entries: List<LibraryEntry>,
        query: String,
        ranked: List<Match>,
        filters: Filters,
        removed: Set<String>,
        shownExtra: Int,
        today: SimpleDate,
    ): SearchOutcome {
        if (query.isBlank()) return SearchOutcome.EMPTY
        val kept = entries.filter { filters.matches(it, today) }
        val words = Library.searchWords(kept, query, Place.ALL).filter { keyOf(it) !in removed }
        val wordKeys = words.map { keyOf(it) }.toSet()
        val allowed = kept.map { it.source.ifBlank { it.path } }.toSet()
        val candidates = ranked.filter { match ->
            val key = keyOf(match)
            key in allowed && key !in removed && key !in wordKeys
        }
        val close = Library.aboveThreshold(candidates, filters.resemblance.floor)
        val closeKeys = close.map { keyOf(it) }.toSet()
        val below = candidates.filter { keyOf(it) !in closeKeys && (it.score ?: 0f) >= MoreResults.FLOOR }
        val offered = below.take(MoreResults.MAX_EXTRA)
        val shown = shownExtra.coerceIn(0, offered.size)
        return SearchOutcome(
            words = words,
            close = close,
            extra = offered.take(shown),
            remaining = offered.size - shown,
            limitReached = below.size > MoreResults.MAX_EXTRA && shown >= offered.size,
        )
    }

    /** Le nombre de photos moins proches après un nouvel appui. */
    fun nextShown(outcome: SearchOutcome): Int = outcome.extra.size + outcome.nextBatch
}
