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
                takenMs = r.takenMs.takeIf { it > 0 },
                addedAt = if (r.modified > 0) r.modified else now,
                analysisVersion = ANALYSIS_VERSION,
                faces = r.faces,
                portrait = r.portrait,
                indoor = r.indoor,
                text = r.text.ifBlank { null },
                vector = vector,
            )
        }
        return Mapped(records, withoutAddress)
    }
}
