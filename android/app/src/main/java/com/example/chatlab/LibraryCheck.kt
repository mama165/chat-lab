package com.example.chatlab

import java.io.Reader

/**
 * Checks the compact store against the JSON library, without changing anything: every photo of the JSON
 * must have a row, and its vector must be exactly the stored one. Only counts are reported.
 */
object LibraryCheck {
    class Report(val checked: Int, val missing: Int, val different: Int, val duplicates: Int)

    /** The best [k] photos of the JSON library for a query, read as a stream: nothing is kept but the top. */
    fun rank(reader: Reader, query: FloatArray, k: Int): List<Pair<String, Float>> {
        val top = TopK<String>(k)
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            if (seen.add(record.address)) top.offer(record.address, Library.dot(query, record.vector))
        }
        return top.sorted()
    }

    /**
     * The word search of the JSON library, read as a stream, with the rules of Library.searchWords: the same
     * photos, in the same order. Returns the addresses.
     */
    fun wordMatches(reader: Reader, words: List<String>, place: Place): List<String> {
        val matches = ArrayList<Pair<String, Int>>()
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            if (!seen.add(record.address)) return@forEachEntry
            if (!inPlace(record.indoor, place)) return@forEachEntry
            val text = Library.fold(record.text ?: "")
            val name = Library.fold(record.name)
            if (words.all { text.contains(it) || name.contains(it) }) {
                matches += record.address to words.count { text.contains(it) }
            }
        }
        return matches.sortedByDescending { it.second }.map { it.first }
    }

    /** The photos of the JSON library that the filters keep, in order, read as a stream. */
    fun filterMatches(reader: Reader, filters: Filters, time: When): List<String> {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            if (!seen.add(record.address)) return@forEachEntry
            if (filters.matches(entryOf(record), time)) out += record.address
        }
        return out
    }

    /** The record as the search screen reads a library entry. */
    fun entryOf(r: PhotoRecord): LibraryEntry = LibraryEntry(
        id = r.address,
        name = r.name,
        path = r.path,
        indoor = r.indoor,
        faces = r.faces,
        text = r.text ?: "",
        taken = r.taken ?: "",
        source = r.address,
        folder = r.folder ?: "",
        mime = r.mime ?: "",
        takenMs = r.takenMs ?: 0L,
        closeUp = r.portrait,
    )

    /** A word of the first text found in the library, to compare the two searches on real data. */
    fun firstWord(reader: Reader): String? {
        var found: String? = null
        KnownSummary.forEachEntry(reader) { item ->
            if (found != null) return@forEachEntry
            val text = Library.fold(item.optString("text"))
            found = text.split(" ").firstOrNull { it.length >= 3 }
        }
        return found
    }

    private fun inPlace(indoor: Boolean?, place: Place): Boolean = when (place) {
        Place.ALL -> true
        Place.INDOOR -> indoor == true
        Place.OUTDOOR -> indoor == false
    }

    /** The same photos on both sides, whatever their order: the ties of the screen are not fixed yet. */
    fun sameSet(a: List<String>, b: List<String>): Boolean = a.size == b.size && a.toSet() == b.toSet()

    /** How many of the addresses are in both lists. */
    fun common(a: List<String>, b: List<String>): Int = a.toSet().intersect(b.toSet()).size

    fun run(reader: Reader, rows: Map<String, Long>, readVector: (Long) -> FloatArray): Report {
        var checked = 0
        var missing = 0
        var different = 0
        var duplicates = 0
        val seen = HashSet<String>()
        KnownSummary.forEachEntry(reader) { item ->
            val record = PhotoRecordFromJson.from(item, now = 0L) ?: return@forEachEntry
            // A photo written twice in the old JSON: the store keeps one, so only the first is compared.
            if (!seen.add(record.address)) {
                duplicates++
                return@forEachEntry
            }
            checked++
            val row = rows[record.address]
            if (row == null) {
                missing++
            } else if (!readVector(row).contentEquals(record.vector)) {
                different++
            }
        }
        return Report(checked, missing, different, duplicates)
    }
}
