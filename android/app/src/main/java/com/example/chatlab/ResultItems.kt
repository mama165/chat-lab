package com.example.chatlab

/** Les éléments de la liste des résultats, dans l'ordre d'affichage. */
sealed interface ResultItem {
    val key: String

    data class Label(val text: String) : ResultItem { override val key = "label-$text" }
    data class Word(val match: Match) : ResultItem { override val key = "w-" + SearchResults.keyOf(match) }
    data object Line : ResultItem { override val key = "line" }
    data class Tile(val match: Match, val isNew: Boolean) : ResultItem { override val key = "t-" + SearchResults.keyOf(match) }
    data class Separator(val text: String) : ResultItem { override val key = "sep" }
    data class More(val text: String) : ResultItem { override val key = "more" }
    data class End(val title: String, val detail: String) : ResultItem { override val key = "end" }
}

fun resultItems(outcome: SearchOutcome, query: String, newFrom: Int): List<ResultItem> = buildList<ResultItem> {
    if (outcome.words.isNotEmpty()) {
        add(ResultItem.Label("Texte lu"))
        outcome.words.forEach { add(ResultItem.Word(it)) }
    }
    if (outcome.close.isNotEmpty() || outcome.extra.isNotEmpty()) {
        if (outcome.words.isNotEmpty()) add(ResultItem.Line)
        add(ResultItem.Label("Ressemblantes"))
        outcome.close.forEach { add(ResultItem.Tile(it, false)) }
    }
    if (outcome.extra.isNotEmpty()) {
        add(ResultItem.Separator("Moins proches · ${outcome.extra.size}"))
        outcome.extra.forEachIndexed { index, match -> add(ResultItem.Tile(match, newFrom in 0..index)) }
    }
    if (outcome.hasMore) {
        val offered = outcome.extra.size + outcome.remaining
        add(
            ResultItem.More(
                if (outcome.extra.isEmpty()) "Voir des photos moins proches (${outcome.nextBatch})"
                else "Voir ${outcome.nextBatch} photos de plus (${outcome.extra.size + outcome.nextBatch} sur $offered)",
            ),
        )
    } else if (outcome.extra.isNotEmpty()) {
        add(
            ResultItem.End(
                "Plus de photos proches de « $query »",
                if (outcome.limitReached) "Pour aller plus loin, choisissez Large dans les filtres."
                else "Les suivantes ne ressemblent plus assez. Essayez d'autres mots, ou retirez un filtre.",
            ),
        )
    }
}
