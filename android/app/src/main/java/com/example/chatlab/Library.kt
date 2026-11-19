package com.example.chatlab

import android.content.Context
import org.json.JSONArray
import java.io.File
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
)

/** Une image retenue par la recherche ; score présent seulement pour la recherche par description. */
data class Match(val entry: LibraryEntry, val score: Float?)

/** Filtre de lieu proposé dans la recherche. */
enum class Place { ALL, INDOOR, OUTDOOR }

/** Critères lus dans une phrase : nombre de personnes et lieu, si la phrase les nomme. */
data class Criteria(val people: Int?, val place: Place?)

/** Le carnet produit par l'analyse, et les deux façons de chercher dedans. */
object Library {
    /** Seuils de SigLIP 2, mesurés sur XM3600 (voir infrastructure/catalog/siglip.go). */
    private const val MARGIN = 0.04f
    private const val FLOOR = 0.08f

    fun load(context: Context): List<LibraryEntry> {
        val file = File(context.filesDir, "library.json")
        if (!file.exists()) return emptyList()
        val array = JSONArray(file.readText(Charsets.UTF_8))
        return (0 until array.length()).map { index ->
            val item = array.getJSONObject(index)
            LibraryEntry(
                id = item.getString("id"),
                name = item.getString("name"),
                path = item.getString("path"),
                indoor = if (item.has("indoor")) item.getBoolean("indoor") else null,
                faces = item.optInt("faces", 0),
                text = item.optString("text", ""),
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

    /**
     * Recherche par description : les images dont le vecteur ressemble à celui de la phrase.
     * Une image est retenue si elle est à MARGIN du meilleur score, et au-dessus de FLOOR.
     */
    fun searchDescription(entries: List<LibraryEntry>, query: FloatArray, place: Place, people: Int?): List<Match> {
        val scored = entries
            .filter { inPlace(it, place) }
            .mapNotNull { entry -> entry.vector?.let { entry to dot(query, it) } }
        val best = scored.maxOfOrNull { it.second } ?: return emptyList()
        return scored
            .filter { (_, score) -> score >= best - MARGIN && score >= FLOOR }
            .filter { (entry, _) -> people == null || kotlin.math.abs(entry.faces - people) <= 1 }
            // With a number of people asked, the pictures with exactly that number come first.
            .sortedWith(compareBy<Pair<LibraryEntry, Float>> { (entry, _) -> people?.let { kotlin.math.abs(entry.faces - it) } ?: 0 }
                .thenByDescending { (_, score) -> score })
            .map { (entry, score) -> Match(entry, score) }
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

    private fun inPlace(entry: LibraryEntry, place: Place): Boolean = when (place) {
        Place.ALL -> true
        Place.INDOOR -> entry.indoor == true
        Place.OUTDOOR -> entry.indoor == false
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
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
