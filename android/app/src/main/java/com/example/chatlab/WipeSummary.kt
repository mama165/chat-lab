package com.example.chatlab

/** Une ligne de la page « Tout effacer » : titre, détail, et vide s'il n'y a rien à effacer. */
data class WipeLine(val title: String, val detail: String, val empty: Boolean)

/** Ce que « Tout effacer » supprimerait, mesuré sur le téléphone de la personne. */
data class WipeSummary(
    val photos: Int,
    val librarySize: Long,
    val removals: Int,
    val recents: List<String>,
    val lastAnalysis: SimpleDate?,
    val hasReport: Boolean,
) {
    val canWipe: Boolean
        get() = photos > 0 || librarySize > 0 || removals > 0 || recents.isNotEmpty() || hasReport

    fun lines(): List<WipeLine> = listOf(
        WipeLine(
            "Le carnet d'analyse",
            if (photos > 0) "${Formats.photos(photos)} analysée${if (photos > 1) "s" else ""} · ${Formats.size(librarySize)}"
            else "aucune photo analysée",
            photos == 0 && librarySize == 0L,
        ),
        WipeLine(
            "Les photos non classifiées",
            when (removals) {
                0 -> "aucune"
                1 -> "1 photo retirée de vos recherches"
                else -> "$removals photos retirées de vos recherches"
            },
            removals == 0,
        ),
        WipeLine(
            "Les recherches récentes",
            if (recents.isEmpty()) "aucune" else recents.take(3).joinToString(", ") + if (recents.size > 3) "…" else "",
            recents.isEmpty(),
        ),
        WipeLine(
            "Le dernier compte rendu",
            when {
                !hasReport -> "aucun"
                lastAnalysis != null -> "Analyse du ${Dates.short(lastAnalysis)}"
                else -> "Dernière analyse"
            },
            !hasReport,
        ),
    )
}
