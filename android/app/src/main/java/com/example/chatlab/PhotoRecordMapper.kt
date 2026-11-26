package com.example.chatlab

/**
 * Turns the results of an analysis into the records of the compact store. No Android here, so that it
 * can be tested on the JVM.
 */
object PhotoRecordMapper {
    /** The records to write, and how many results had no address and were left out. */
    class Mapped(val records: List<PhotoRecord>, val withoutAddress: Int)

    fun map(results: List<ImageResult>, now: Long): Mapped {
        val records = ArrayList<PhotoRecord>()
        var withoutAddress = 0
        for (r in results) {
            // The same rule as the JSON writer: a failed photo, or one without a vector, is not stored.
            if (r.error != null) continue
            val vector = r.vector ?: continue
            // A photo without an address cannot be told apart from another one: it is not stored.
            if (r.source.isBlank()) {
                withoutAddress++
                continue
            }
            records += PhotoRecord(
                address = r.source,
                name = r.file.name,
                path = r.source,
                folder = r.folder.ifBlank { null },
                mime = r.mime.ifBlank { null },
                size = r.size,
                modified = r.modified.takeIf { it > 0 },
                taken = r.taken.ifBlank { null },
                takenMs = TakenTime.millis(r.takenMs, r.taken),
                addedAt = if (r.modified > 0) r.modified else now,
                analysisVersion = ANALYSIS_VERSION,
                faces = r.faces,
                portrait = r.portrait,
                indoor = r.indoor,
                text = null,
                vector = vector,
            )
        }
        return Mapped(records, withoutAddress)
    }
}

/** The same mapping, for a record of the JSON library being migrated. */
object PhotoRecordFromJson {
    private const val VECTOR_SIZE = 768
    private const val ADDED_FORMAT = "yyyy-MM-dd'T'HH:mm:ss'Z'"

    /** Null when the entry cannot be stored: no address, no name, or a vector that is not 768 numbers. */
    fun from(item: org.json.JSONObject, now: Long): PhotoRecord? {
        val address = item.optString("source").ifBlank { return null }
        val name = item.optString("name").ifBlank { return null }
        val values = item.optJSONArray("vectors")?.optJSONArray(0) ?: return null
        if (values.length() != VECTOR_SIZE) return null
        val vector = FloatArray(VECTOR_SIZE) { values.getDouble(it).toFloat() }
        return PhotoRecord(
            address = address,
            name = name,
            path = address,
            folder = item.optString("folder").ifBlank { null },
            mime = item.optString("mime").ifBlank { null },
            size = item.optLong("size", 0L),
            modified = item.optLong("modified", 0L).takeIf { it > 0 },
            taken = item.optString("taken").ifBlank { null },
            takenMs = TakenTime.millis(item.optLong("taken_ms", 0L), item.optString("taken")),
            addedAt = addedAt(item.optString("added_at")) ?: now,
            analysisVersion = item.optInt("analysis_version", 0),
            faces = item.optInt("faces", 0),
            portrait = item.optBoolean("portrait", false),
            indoor = if (item.has("indoor")) item.optBoolean("indoor") else null,
            text = item.optString("text").ifBlank { null },
            vector = vector,
        )
    }

    private fun addedAt(text: String): Long? {
        if (text.isBlank()) return null
        return try {
            java.text.SimpleDateFormat(ADDED_FORMAT, java.util.Locale.US)
                .apply { timeZone = java.util.TimeZone.getTimeZone("UTC") }
                .parse(text)?.time
        } catch (e: java.text.ParseException) {
            null
        }
    }
}
