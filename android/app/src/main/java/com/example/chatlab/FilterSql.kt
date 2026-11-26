package com.example.chatlab

/**
 * The filters as one SQL query. It keeps every photo that Filters.matches could keep, and may keep a few
 * more: the exact rules are applied afterwards, with Filters.matches, so the result is always the same.
 * The date ranges with a time of the day are left to that exact check.
 */
object FilterSql {
    fun query(filters: Filters, time: When): WordSearch.Query {
        val where = ArrayList<String>()
        val args = ArrayList<String>()
        val date = filters.date
        if (date.isActive && date.range == null) {
            val (start, end) = DateFilter.window(date.preset, time)
            where += "taken_ms BETWEEN $start AND $end"
        }
        when (filters.place) {
            Place.ALL -> Unit
            Place.INDOOR -> where += "indoor = 1"
            Place.OUTDOOR -> where += "indoor = 0"
        }
        when (filters.people) {
            People.ANY -> Unit
            People.NONE -> where += "faces = 0"
            People.ONE -> where += "faces = 1"
            People.MANY -> where += "faces >= 2"
        }
        if (filters.closeUp) where += "portrait = 1"
        // Without text cannot be narrowed in SQL (a blank text is blank in Kotlin): it is left to the exact check.
        if (filters.folders.isNotEmpty()) {
            where += "COALESCE(folder, '') IN (${marks(filters.folders.size)})"
            args += filters.folders
        }
        if (filters.types.isNotEmpty()) {
            where += "COALESCE(mime, '') IN (${marks(filters.types.size)})"
            args += filters.types
        }
        when (filters.kind) {
            FileKind.ANY -> Unit
            FileKind.IMAGE -> {
                where += "COALESCE(mime, '') IN (${marks(FileSniffer.PICTURES.size)})"
                args += FileSniffer.PICTURES.toList()
            }
            FileKind.PDF -> {
                where += "COALESCE(mime, '') = ?"
                args += FileSniffer.PDF
            }
        }
        val condition = if (where.isEmpty()) "1 = 1" else where.joinToString(" AND ")
        return WordSearch.Query("SELECT address FROM photo WHERE $condition ORDER BY vector_row ASC", args)
    }

    private fun marks(count: Int): String = List(count) { "?" }.joinToString(",")
}
