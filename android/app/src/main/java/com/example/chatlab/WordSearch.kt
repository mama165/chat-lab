package com.example.chatlab

/**
 * The word search of the library, as one SQL query. The same rules as Library.searchWords: a photo matches
 * when every word is in its normalised text or name, and the photos are ordered by the number of words
 * their text contains.
 */
object WordSearch {
    class Query(val sql: String, val args: List<String>)

    /** The words of a query, normalised like the stored text. */
    fun words(query: String): List<String> = Library.fold(query).split(" ").filter { it.isNotBlank() }

    fun query(words: List<String>, place: Place): Query {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        when (place) {
            Place.ALL -> Unit
            Place.INDOOR -> where += "indoor = 1"
            Place.OUTDOOR -> where += "indoor = 0"
        }
        for (word in words) {
            where += "(text_fold LIKE ? ESCAPE '\\' OR name_fold LIKE ? ESCAPE '\\')"
            args += pattern(word)
            args += pattern(word)
        }
        val condition = if (where.isEmpty()) "1 = 1" else where.joinToString(" AND ")
        val score = if (words.isEmpty()) "0" else words.joinToString(" + ") {
            "(CASE WHEN text_fold LIKE ? ESCAPE '\\' THEN 1 ELSE 0 END)"
        }
        val scoreArgs = words.map { pattern(it) }
        val sql = "SELECT address FROM photo WHERE $condition ORDER BY ($score) DESC, vector_row ASC"
        return Query(sql, args + scoreArgs)
    }

    /** A LIKE pattern that matches the word anywhere, with the wildcard characters taken literally. */
    fun pattern(word: String): String {
        val escaped = word.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
        return "%$escaped%"
    }
}
