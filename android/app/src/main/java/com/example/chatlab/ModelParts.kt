package com.example.chatlab

/** Les morceaux « nom.partN » d'un modèle trop gros pour être envoyé d'un seul bloc. */
object ModelParts {
    private val PART = Regex("\\.part(\\d+)$")

    /** Le numéro du morceau, ou null si le nom n'est pas un morceau de [base]. */
    fun index(name: String, base: String): Int? {
        if (!name.startsWith("$base.part")) return null
        return PART.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { name == "$base.part$it" }
    }

    /** Les noms des morceaux de [base], dans l'ordre de leur numéro (part2 avant part10). */
    fun ordered(names: Collection<String>, base: String): List<String> =
        names.mapNotNull { name -> index(name, base)?.let { it to name } }.sortedBy { it.first }.map { it.second }
}
