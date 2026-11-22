package com.example.chatlab

import org.json.JSONArray
import java.text.Normalizer

/** Une image du carnet, telle que l'analyse l'a écrite. */
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
    /** Nom du dossier source (« Camera », « Screenshots »), vide pour un ancien carnet. */
    val folder: String = "",
    /** Type MIME détecté par les octets du fichier (« image/jpeg »), vide pour un ancien carnet. */
    val mime: String = "",
    /** Instant de prise de vue en millisecondes, 0 si inconnu. */
    val takenMs: Long = 0L,
    /** Le visage principal occupe une bonne part de la photo (portrait). */
    val closeUp: Boolean = false,
)

/** Une image retenue par la recherche ; score présent seulement pour la recherche par description. */
data class Match(val entry: LibraryEntry, val score: Float?)

/** Filtre de lieu proposé dans la recherche. */
enum class Place { ALL, INDOOR, OUTDOOR }

/** Critères lus dans une phrase : nombre de personnes et lieu, si la phrase les nomme. */
data class Criteria(val people: Int?, val place: Place?)

/** Le carnet produit par l'analyse, et les deux façons de chercher dedans. */
object Library {
    /** Lit le texte du carnet (library.json). Un carnet illisible donne une liste vide. */
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
     * Recherche par mots : les images qui contiennent tous les mots dans le texte lu ou dans le nom.
     * Insensible à la casse et aux accents ; un mot incomplet suffit ("fact" trouve "facture").
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

    /** Tous les résultats classés, sans limite : le seuil choisit ensuite ce qui s'affiche. */
    fun rankDescription(entries: List<LibraryEntry>, query: FloatArray, place: Place, people: Int?): List<Match> =
        entries
            .filter { inPlace(it, place) }
            .filter { entry -> people == null || entry.faces == people }
            .mapNotNull { entry -> entry.vector?.let { Match(entry, dot(query, it)) } }
            .sortedByDescending { it.score ?: 0f }

    /** Les résultats au-dessus du plancher et assez proches du meilleur (à la marge près). */
    fun aboveThreshold(ranked: List<Match>, floor: Float, margin: Float = 0.04f): List<Match> {
        val best = ranked.firstOrNull()?.score ?: return emptyList()
        return ranked.filter { match ->
            val score = match.score ?: 0f
            score >= floor && score >= best - margin
        }
    }

    /**
     * Lit dans la phrase le nombre de personnes (« deux personnes », « 3 visages ») et le lieu
     * (« en intérieur », « en extérieur »). Une phrase qui ne les nomme pas ne filtre pas.
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

    /** Minuscules, sans accents, ponctuation remplacée par des espaces. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
