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
)

/** Filtre de lieu proposé dans la recherche. */
enum class Place { ALL, INDOOR, OUTDOOR }

/** Le carnet produit par l'analyse, et la recherche par mot-clé dedans. */
object Library {
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
            )
        }
    }

    /**
     * Les images qui contiennent tous les mots de la recherche, dans le texte lu ou dans le nom.
     * Insensible à la casse et aux accents ; un mot tapé partiellement suffit ("fact" trouve "facture").
     * Les images où plus de mots apparaissent dans le texte viennent en premier.
     */
    fun search(entries: List<LibraryEntry>, query: String, place: Place): List<LibraryEntry> {
        val words = fold(query).split(" ").filter { it.isNotBlank() }
        return entries
            .filter { entry ->
                when (place) {
                    Place.ALL -> true
                    Place.INDOOR -> entry.indoor == true
                    Place.OUTDOOR -> entry.indoor == false
                }
            }
            .filter { entry ->
                val text = fold(entry.text)
                val name = fold(entry.name)
                words.all { word -> text.contains(word) || name.contains(word) }
            }
            .sortedByDescending { entry -> words.count { word -> fold(entry.text).contains(word) } }
    }

    /** Minuscules, sans accents, ponctuation remplacée par des espaces. */
    fun fold(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}
