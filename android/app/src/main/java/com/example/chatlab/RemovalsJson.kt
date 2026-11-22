package com.example.chatlab

import org.json.JSONArray
import org.json.JSONObject

/** Une photo retirée d'une recherche, avec la date du retrait. */
data class Removal(val query: String, val source: String, val date: String)

/**
 * Le contenu de retraits.json : pour chaque recherche, les photos écartées avec leur date.
 * Format : { "mot": [ {"source": "...", "date": "jj/mm/aaaa"} ] }. Les anciennes entrées,
 * simples chaînes sans date, restent lues.
 */
object RemovalsJson {
    fun all(json: JSONObject): List<Removal> =
        json.keys().asSequence().toList().flatMap { query ->
            val array = json.optJSONArray(query) ?: JSONArray()
            (0 until array.length()).map { index ->
                val item = array.opt(index)
                if (item is JSONObject) Removal(query, item.optString("source"), item.optString("date", ""))
                else Removal(query, item.toString(), "")
            }
        }.sortedWith(compareBy<Removal>({ it.query }, { it.date }))

    fun sourcesFor(json: JSONObject, query: String): Set<String> {
        val array = json.optJSONArray(query) ?: return emptySet()
        return (0 until array.length()).map { index -> sourceOf(array.opt(index)) }.toSet()
    }

    /** Ajoute des photos retirées pour une recherche ; une photo déjà retirée n'est pas doublée. */
    fun add(json: JSONObject, query: String, sources: Collection<String>, date: String): JSONObject {
        val array = json.optJSONArray(query) ?: JSONArray().also { json.put(query, it) }
        val already = (0 until array.length()).map { sourceOf(array.opt(it)) }.toMutableSet()
        for (source in sources) {
            if (source.isBlank() || !already.add(source)) continue
            array.put(JSONObject().put("source", source).put("date", date))
        }
        return json
    }

    /** Rétablit une photo ; une recherche sans plus aucun retrait disparaît du fichier. */
    fun restore(json: JSONObject, query: String, source: String): JSONObject {
        val array = json.optJSONArray(query) ?: return json
        val kept = JSONArray()
        for (index in 0 until array.length()) {
            val item = array.opt(index)
            if (sourceOf(item) != source) kept.put(item)
        }
        if (kept.length() == 0) json.remove(query) else json.put(query, kept)
        return json
    }

    fun count(json: JSONObject): Int = json.keys().asSequence().sumOf { json.optJSONArray(it)?.length() ?: 0 }

    private fun sourceOf(item: Any?): String = if (item is JSONObject) item.optString("source") else item.toString()
}
