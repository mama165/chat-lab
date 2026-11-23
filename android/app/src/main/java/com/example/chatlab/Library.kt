package com.example.chatlab

import org.json.JSONArray
import java.text.Normalizer

/** An image of the library, as the analysis wrote it. */
data class LibraryEntry(
    val id: String,
    val name: String,
    val path: String,
    val indoor: Boolean?,
    val faces: Int,
    val text: String,
    val vector: FloatArray? = null,
    val taken: String = "",
    val source: String = "",
    /** Name of the source folder ("Camera", "Screenshots"), empty for an old library. */
    val folder: String = "",
    /** MIME type detected from the bytes of the file ("image/jpeg"), empty for an old library. */
    val mime: String = "",
    /** Instant de prise de vue en millisecondes, 0 si inconnu. */
    val takenMs: Long = 0L,
    /** The main face takes up a good part of the photo (portrait). */
    val closeUp: Boolean = false,
)

/** An image kept by the search; score present only for the search by description. */
data class Match(val entry: LibraryEntry, val score: Float?)

/** Place filter offered in the search. */
enum class Place { ALL, INDOOR, OUTDOOR }

/** Criteria read from a sentence: number of people and place, if the sentence names them. */
data class Criteria(val people: Int?, val place: Place?)

/** The library produced by the analysis, and the two ways to search in it. */
object Library {
    /** Reads the text of the library (library.json). An unreadable library gives an empty list. */
    fun parse(text: String?, zone: java.util.TimeZone = java.util.TimeZone.getDefault()): List<LibraryEntry> {
        if (text.isNullOrBlank()) return emptyList()
        val array = try {
            JSONArray(text)
        } catch (e: Exception) {
            return emptyList()
        }
        return (0 until array.length()).mapNotNull { index ->
            val item = array.optJSONObject(index) ?: return@mapNotNull null
            LibraryEntry(
                id = item.optString("id", ""),
                name = item.optString("name", ""),
                path = item.optString("path", ""),
                indoor = if (item.has("indoor")) item.optBoolean("indoor") else null,
                faces = item.optInt("faces", 0),
                text = item.optString("text", ""),
                taken = item.optString("taken", ""),
                source = item.optString("source", ""),
                folder = item.optString("folder", ""),
                mime = item.optString("mime", ""),
                takenMs = item.optLong("taken_ms", 0L).takeIf { it > 0 }
                    ?: Dates.parseTakenMillis(item.optString("taken", ""), zone) ?: 0L,
                closeUp = item.optBoolean("portrait", false),
                vector = item.optJSONArray("vectors")?.optJSONArray(0)?.let { values ->
                    FloatArray(values.length()) { values.getDouble(it).toFloat() }
                },
            )
        }
    }

    /**
     * Search by words: the images that contain all the words in the text read or in the name.
     * Case and accent insensitive; an incomplete word is enough ("fact" finds "facture").
     */
    fun searchWords(entries: List<LibraryEntry>, query: String, place: Place): List<Match> {
        val words = fold(query).split(" ").filter { it.isNotBlank() }
        return entries
            .filter { inPlace(it, place) }
            .filter { entry ->
                val text = fold(entry.text)
                val name = fold(entry.name)
                words.all { word -> text.contains(word) || name.contains(word) }
            }
            .sortedByDescending { entry -> words.count { word -> fold(entry.text).contains(word) } }
            .map { Match(it, null) }
    }

    /** All the results ranked, without limit: the threshold then chooses what is shown. */
    fun rankDescription(entries: List<LibraryEntry>, query: FloatArray, place: Place, people: Int?): List<Match> =
        entries
            .filter { inPlace(it, place) }
            .filter { entry -> people == null || entry.faces == people }
            .mapNotNull { entry -> entry.vector?.let { Match(entry, dot(query, it)) } }
            .sortedByDescending { it.score ?: 0f }

    /** The results above the floor and close enough to the best (within the margin). */
    fun aboveThreshold(ranked: List<Match>, floor: Float, margin: Float = 0.04f): List<Match> {
        val best = ranked.firstOrNull()?.score ?: return emptyList()
        return ranked.filter { match ->
            val score = match.score ?: 0f
            score >= floor && score >= best - margin
        }
    }

    /**
     * Reads in the sentence the number of people ("deux personnes", "3 visages") and the place
     * ("en intérieur", "en extérieur"). A sentence that does not name them does not filter.
     */
    fun criteria(sentence: String): Criteria {
        val text = fold(sentence)
        val people = Regex("\\b(\\d+|un|une|deux|trois|quatre|cinq|six|sept|huit|neuf|dix) (personnes?|visages?|gens)\\b")
            .find(text)?.groupValues?.get(1)?.let { number(it) }
        val place = when {
            Regex("\\b(interieur|dans une piece)\\b").containsMatchIn(text) -> Place.INDOOR
            Regex("\\b(exterieur|dehors|plein air)\\b").containsMatchIn(text) -> Place.OUTDOOR
            else -> null
        }
        return Criteria(people, place)
    }

    private fun number(word: String): Int? = word.toIntOrNull() ?: when (word) {
        "un", "une" -> 1
        "deux" -> 2
        "trois" -> 3
        "quatre" -> 4
        "cinq" -> 5
        "six" -> 6
        "sept" -> 7
        "huit" -> 8
        "neuf" -> 9
        "dix" -> 10
        else -> null
    }

    fun inPlace(entry: LibraryEntry, place: Place): Boolean = when (place) {
        Place.ALL -> true
        Place.INDOOR -> entry.indoor == true
        Place.OUTDOOR -> entry.indoor == false
    }

    fun dot(a: FloatArray, b: FloatArray): Float {
        var sum = 0f
        for (i in a.indices) sum += a[i] * b[i]
        return sum
    }

    /** Lowercase, without accents, punctuation replaced by spaces. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
