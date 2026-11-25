package com.example.chatlab

/** The pieces "nom.partN" of a model too big to be sent in one block. */
object ModelParts {
    private val PART = Regex("\\.part(\\d+)$")

    /** The number of the piece, or null if the name is not a piece of [base]. */
    fun index(name: String, base: String): Int? {
        if (!name.startsWith("$base.part")) return null
        return PART.find(name)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { name == "$base.part$it" }
    }

    /** The names of the pieces of [base], in the order of their number (part2 before part10). */
    fun ordered(names: Collection<String>, base: String): List<String> =
        names.mapNotNull { name -> index(name, base)?.let { it to name } }.sortedBy { it.first }.map { it.second }
}
